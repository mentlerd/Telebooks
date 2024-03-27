package com.mentlerd;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.api.DedicatedServerModInitializer;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.*;
import net.minecraft.util.dynamic.Codecs;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

@SuppressWarnings("unused")
public class Telebooks implements DedicatedServerModInitializer {
	public static final String MOD_ID = "telebooks";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	static Vec3d rotateOffset(Vec3d vec, BlockRotation rotation) {
		return switch (rotation) {
			case NONE -> vec;
			case CLOCKWISE_90 -> new Vec3d(-vec.getZ(), vec.getY(), vec.getX());
			case CLOCKWISE_180 -> new Vec3d(-vec.getX(), vec.getY(), -vec.getZ());
			case COUNTERCLOCKWISE_90 -> new Vec3d(vec.getZ(), vec.getY(), -vec.getX());
		};
	}
	static float rotateYaw(float yaw, BlockRotation rotation) {
		return switch (rotation) {
			case NONE -> yaw;
			case CLOCKWISE_90 -> yaw + 90.0F;
			case CLOCKWISE_180 -> yaw + 180.0F;
			case COUNTERCLOCKWISE_90 -> yaw + 270.0F;
		};
	}

	static class TeleportVolume {
		record BlockInfo(Vec3i offset, BlockState state, NbtCompound blockEntityData) {}
		record EntityInfo(Vec3d offset, EntityType<?> type, NbtCompound entityData) {}
		record PlayerInfo(Vec3d offset, float yaw, PlayerEntity player) {}

		private final ArrayList<BlockInfo> blocks = new ArrayList<>();
		private final ArrayList<EntityInfo> entities = new ArrayList<>();
		private final ArrayList<PlayerInfo> players = new ArrayList<>();

		// Passenger state is difficult to restore - serialized vehicles omit player passengers from
		//  their list, but their order may matter (such as which player is controlling the boat)
		record PlayerPassengerInfo(UUID player, int index) {}

		private final HashMap<UUID, ArrayList<PlayerPassengerInfo>> playerPassengers = new HashMap<>();

		public void cut(WorldAccess world, BlockPos center, Direction forward) {
			var right = forward.rotateYClockwise();

			var mins = new Vec3i(-1, 1, -1);
			var maxs = new Vec3i(1, 4, 1);

			var rotation = switch (forward) {
				case UP, DOWN -> throw new IllegalArgumentException();

				case EAST -> BlockRotation.NONE;
				case SOUTH -> BlockRotation.COUNTERCLOCKWISE_90;
				case WEST -> BlockRotation.CLOCKWISE_180;
				case NORTH -> BlockRotation.CLOCKWISE_90;
			};

			for (int offX = mins.getX(); offX <= maxs.getX(); offX++) {
				for (int offY = mins.getY(); offY <= maxs.getY(); offY++) {
					for (int offZ = mins.getZ(); offZ <= maxs.getZ(); offZ++) {
						var pos = center
								.offset(forward, offX)
								.offset(Direction.UP, offY)
								.offset(right, offZ);

						var offset = new Vec3i(offX, offY, offZ);
						var state = world.getBlockState(pos).rotate(rotation);

						NbtCompound blockEntityData = null;

						var blockEntity = world.getBlockEntity(pos);
						if (blockEntity != null) {
							blockEntityData = blockEntity.createNbt();

							// Clear inventories of block entities before changing them
							//  to air, otherwise they would drop their contents
							Clearable.clear(blockEntity);
						}

						blocks.add(new BlockInfo(offset, state, blockEntityData));
					}
				}
			}

			var centerPos = center.toCenterPos();

			// NB: -0.5 extension is to support dropped items
			var entityBox = new Box(
				centerPos.add(mins.getX(), mins.getY(), mins.getZ()).add(0, -0.5, 0),
				centerPos.add(maxs.getX(), maxs.getY(), maxs.getZ())
			);

			var capturedEntities = new HashSet<Entity>();

			for (var entity : world.getNonSpectatingEntities(Entity.class, entityBox)) {
				var offset = rotateOffset(entity.getPos().subtract(centerPos), rotation);

				// Players are an exception, they are truly transferred instead of being copied
				if (entity.isPlayer()) {
					var player = (PlayerEntity) entity;
					var yaw = rotateYaw(player.getYaw(), rotation);

					players.add(new PlayerInfo(offset, yaw, player));
					continue;
				}

				// Passenger information must be encoded, regardless of whether the entity
				//  is a serialization root, or is serialized as part of another vehicle
				{
					var passengerInfo = new ArrayList<PlayerPassengerInfo>();
					var passengers = entity.getPassengerList();

					for (int index = 0; index < passengers.size(); index++) {
						var passenger = passengers.get(index);

						if (passenger.isPlayer()) {
							passengerInfo.add(new PlayerPassengerInfo(passenger.getUuid(), index));
						}
					}

					if (!passengerInfo.isEmpty()) {
						playerPassengers.put(entity.getUuid(), passengerInfo);
					}
				}

				// Entities within vehicles are saved together with the vehicle
				if (entity.hasVehicle()) {
					continue;
				}

				// Make sure entities are not captured twice
				if (capturedEntities.contains(entity)) {
					continue;
				}

				var entityData = new NbtCompound();

				if (!entity.saveNbt(entityData)) {
					LOGGER.error("Failed to serialize entity");
					continue;
				}

				entities.add(new EntityInfo(offset, entity.getType(), entityData));

				entity.streamSelfAndPassengers().filter(Predicate.not(Entity::isPlayer)).forEach(capturedEntities::add);
			}

			capturedEntities.forEach(Entity::discard);
		}

		public void paste(ServerWorld world, BlockPos center, Direction forward) {
			var right = forward.rotateYClockwise();

			var rotation = switch (forward) {
				case UP, DOWN -> throw new IllegalArgumentException();

				case EAST -> BlockRotation.NONE;
				case SOUTH -> BlockRotation.CLOCKWISE_90;
				case WEST -> BlockRotation.CLOCKWISE_180;
				case NORTH -> BlockRotation.COUNTERCLOCKWISE_90;
			};

			for (var info : blocks) {
				var pos = center
						.offset(forward, info.offset.getX())
						.offset(Direction.UP, info.offset.getY())
						.offset(right, info.offset.getZ());

				Clearable.clear(world.getBlockEntity(pos));

				world.setBlockState(pos, info.state.rotate(rotation), Block.NOTIFY_LISTENERS);

				if (info.blockEntityData != null) {
					var blockEntity = world.getBlockEntity(pos);
					if (blockEntity == null) {
						LOGGER.warn("Copied block has no block entity?! {}", pos);
						continue;
					}

					blockEntity.readNbt(info.blockEntityData);
					blockEntity.markDirty();
				}
			}

			var loadedEntities = new HashMap<UUID, Entity>();
			var centerPos = center.toCenterPos();

			for (var info : entities) {
				var pos = centerPos.add(rotateOffset(info.offset, rotation));

				var posList = new NbtList();
				posList.add(NbtDouble.of(pos.getX()));
				posList.add(NbtDouble.of(pos.getY()));
				posList.add(NbtDouble.of(pos.getZ()));

				var spawnData = info.entityData.copy();
				spawnData.put("Pos", posList);

				var entity = EntityType.loadEntityWithPassengers(spawnData, world, loaded -> {
					loadedEntities.put(loaded.getUuid(), loaded);
					return loaded;
				});
				if (entity == null) {
					LOGGER.warn("Failed to load copied entity");
					continue;
				}

				// Make entity adjust to its new position
				entity.refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), entity.getYaw() + entity.applyRotation(rotation), entity.getPitch());

				if (!world.spawnNewEntityAndPassengers(entity)) {
					LOGGER.warn("Failed to spawn copied entity");
					continue;
				}
			}

			var playerPosFlags = EnumSet.noneOf(PositionFlag.class);

			for (var info : players) {
				var pos = centerPos.add(rotateOffset(info.offset, rotation));
				var yaw = rotateYaw(info.yaw, rotation);

				var player = info.player;

				player.teleport(world, pos.x, pos.y, pos.z, playerPosFlags, yaw, player.getPitch());
				player.dismountVehicle();
			}

			for (var entry : playerPassengers.entrySet()) {
				var vehicle = loadedEntities.get(entry.getKey());
				if (vehicle == null) {
					continue;
				}

				// Current passenger list, players omitted. Restoring the pre-save list is
				//  possible by inserting all players into the list at their respective
				//  positions, and re-mounting everyone onto the vehicle
				var passengersWithPlayers = new ArrayList<>(vehicle.getPassengerList());

				for (var info : entry.getValue()) {
					var player = world.getPlayerByUuid(info.player);

					passengersWithPlayers.add(info.index, player);
				}

				passengersWithPlayers.forEach(Entity::dismountVehicle);
				passengersWithPlayers.forEach(passenger -> {
					passenger.startRiding(vehicle);
				});
			}
		}
	}

	static class TransformedWorld {
		private final World world;
		private final BlockPos origin;

		private final BlockRotation rotationInto;
		private final BlockRotation rotationFrom;

		public TransformedWorld(World world, BlockPos origin, Direction forward) {
			this.world = world;
			this.origin = origin;

			this.rotationInto = switch (forward) {
				case UP, DOWN -> throw new IllegalArgumentException();

				case EAST -> BlockRotation.NONE;
				case SOUTH -> BlockRotation.COUNTERCLOCKWISE_90;
				case WEST -> BlockRotation.CLOCKWISE_180;
				case NORTH -> BlockRotation.CLOCKWISE_90;
			};
			this.rotationFrom = switch (forward) {
				case UP, DOWN -> throw new IllegalArgumentException();

				case EAST -> BlockRotation.NONE;
				case SOUTH -> BlockRotation.CLOCKWISE_90;
				case WEST -> BlockRotation.CLOCKWISE_180;
				case NORTH -> BlockRotation.COUNTERCLOCKWISE_90;
			};
		}

		public BlockPos localToGlobal(BlockPos pos) {
			return pos.rotate(rotationFrom).add(origin);
		}
		public BlockPos localToGlobal(int x, int y, int z) {
			return localToGlobal(new BlockPos(x, y, z));
		}

		public BlockState getBlockState(BlockPos pos) {
			return world.getBlockState(localToGlobal(pos)).rotate(rotationInto);
		}
		public BlockState getBlockState(int x, int y, int z) {
			return getBlockState(new BlockPos(x, y, z));
		}
	}

	static Optional<List<BlockState>> tryGetBookPattern(World world, BlockPos center, Direction forward) {
		var transformed = new TransformedWorld(world, center, forward);

		final int patternRadius = 1;

		// Requirement: valid patterns are surrounded by an obsidian frame
		final int frameRadius = patternRadius + 1;
		final var frameMaterial = Blocks.OBSIDIAN;

		for (int off = -frameRadius; off < frameRadius; off++) {
			if (!transformed.getBlockState(off, 0, +frameRadius).isOf(frameMaterial)) {
				return Optional.empty();
			}
			if (!transformed.getBlockState(off, 0, -frameRadius).isOf(frameMaterial)) {
				return Optional.empty();
			}
			if (!transformed.getBlockState(+frameRadius, 0, off).isOf(frameMaterial)) {
				return Optional.empty();
			}
			if (!transformed.getBlockState(-frameRadius, 0, off).isOf(frameMaterial)) {
				return Optional.empty();
			}
		}

		// Actual pattern is parsed in the local coordinate system
		var pattern = new ArrayList<BlockState>();

		for (int offX = -1; offX <= 1; offX++) {
			for (int offZ = -1; offZ <= 1; offZ++) {
				var pos = transformed.localToGlobal(offX, 0, offZ);
				var state = world.getBlockState(pos);

				// Requirement: patterns must be made of solid blocks
				if (!state.isOpaqueFullCube(world, pos) && !state.isFullCube(world, pos)) {
					return Optional.empty();
				}

				// Tweak: ignore individual properties of blocks, only care about the type
				pattern.add(state.getBlock().getDefaultState());
			}
		}

		return Optional.of(pattern);
	}

	record BookLocation(RegistryKey<World> world, BlockPos center, Direction forward) {
		static final Codec<BookLocation> CODEC = RecordCodecBuilder.create(instance ->
				instance.group(
						World.CODEC.fieldOf("world").forGetter(BookLocation::world),
						BlockPos.CODEC.fieldOf("center").forGetter(BookLocation::center),
						Direction.CODEC.fieldOf("forward").forGetter(BookLocation::forward)
				).apply(instance, BookLocation::new)
		);

		public Box safeArea() {
			return new Box(center).expand(1.45, 3, 1.45);
		}

		public boolean overlaps(BookLocation other) {
			if (world != other.world) {
				return false;
			}

			return safeArea().intersects(other.safeArea());
		}
	}
	record BookChain(int id, List<BlockState> pattern, List<BookLocation> books) {
		static final Codec<BookChain> CODEC = RecordCodecBuilder.create(instance ->
				instance.group(
						Codecs.NONNEGATIVE_INT.fieldOf("id").forGetter(BookChain::id),
						BlockState.CODEC.listOf().fieldOf("pattern").forGetter(BookChain::pattern),
						BookLocation.CODEC.listOf().fieldOf("books").forGetter(BookChain::books)
				).apply(instance, BookChain::new)
		);

		public BookChain(int id, List<BlockState> pattern) {
			this(id, pattern, new ArrayList<>());
		}

		public BookChain {
			books = new ArrayList<>(books);
		}
	}
	record Database(List<BookChain> books) {
		static final Codec<Database> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					BookChain.CODEC.listOf().fieldOf("chains").forGetter(Database::books)
			).apply(instance, Database::new)
		);

		public Database() {
			this(new ArrayList<>());
		}

		public Database {
			books = new ArrayList<>(books);
		}
	}

	static class State extends PersistentState {
		private static final Type<State> type = new Type<>(State::new, nbt -> {
			var value = new State();
			value.readNbt(nbt);
			return value;
		}, null);

		private static PersistentStateManager getHost(MinecraftServer server) {
			return Objects.requireNonNull(server.getWorld(World.OVERWORLD)).getPersistentStateManager();
		}

		public static State get(MinecraftServer server) {
			return getHost(server).getOrCreate(type, MOD_ID);
		}
		public static void save(MinecraftServer server) {
			var host = getHost(server);

			host.getOrCreate(type, MOD_ID).markDirty();
			host.save();
		}

		int nextChainID = 0;

		final HashMap<Integer, BookChain> books = new HashMap<>();
		final HashMap<List<BlockState>, Integer> patternToBook = new HashMap<>();

		public void readNbt(NbtCompound nbt) {
			var database = Database.CODEC.parse(NbtOps.INSTANCE, nbt).resultOrPartial(LOGGER::error).orElseThrow();

			nextChainID = 0;
			books.clear();
			patternToBook.clear();

			for (var book : database.books) {
				nextChainID = Math.max(nextChainID, book.id + 1);
				books.put(book.id, book);
				patternToBook.put(book.pattern, book.id);
			}
		}

		@Override
		public NbtCompound writeNbt(NbtCompound nbt) {
			var database = new Database();

            database.books.addAll(books.values());

			return (NbtCompound) Database.CODEC.encodeStart(NbtOps.INSTANCE, database).resultOrPartial(LOGGER::error).orElseThrow();
		}

		public BookChain getOrCreateBookChain(List<BlockState> pattern) {
			var existingID = patternToBook.get(pattern);
			if (existingID == null) {
				var id = nextChainID++;
				var chain = new BookChain(id, pattern);

				books.put(id, chain);
				patternToBook.put(pattern, id);

				return chain;
			}

			return books.get(existingID);
		}
	}

	private ActionResult handleLecternUse(ServerWorld world, BlockPos pos) {
		var server = world.getServer();

		// Requirement: teleport lecterns must have a book
		var blockEntity = world.getBlockEntity(pos, BlockEntityType.LECTERN);
		if (blockEntity.isEmpty() || !blockEntity.get().hasBook()) {
			return ActionResult.PASS;
		}

		var forward = world.getBlockState(pos).get(LecternBlock.FACING);
		var center = pos.offset(forward, 2).offset(Direction.DOWN);

		// Check whether this lectern sits on top of a valid book pattern
		var pattern = tryGetBookPattern(world, center, forward);
		if (pattern.isEmpty()) {
			return ActionResult.PASS;
		}

		// If so, locate corresponding book chain
		var book = new BookLocation(world.getRegistryKey(), center, forward);

		var state = State.get(server);
		var stateChanged = false;

		var chain = state.getOrCreateBookChain(pattern.get());

		// Filter books which are no longer valid
		stateChanged |= chain.books.removeIf(loc -> {
			var world2 = server.getWorld(loc.world);

			var candidatePattern = tryGetBookPattern(world2, loc.center, loc.forward);
			if (candidatePattern.isEmpty()) {
				return true;
			}

			return !pattern.equals(candidatePattern);
		});

		// Make sure the activated book does not overlap the volume of any still valid
		//  books, otherwise we cannot let it enter the family
		if (chain.books.stream().filter(Predicate.not(book::equals)).anyMatch(book::overlaps)) {
			return ActionResult.PASS;
		}

		// Check whether this book is a new joiner, and add it to the chain
		if (!chain.books.contains(book)) {
			chain.books.add(book);
			stateChanged = true;
		}

		// Ensure changes are persisted
		if (stateChanged) {
			State.save(server);
		}

		// We might not have enough books intact to perform teleportation
		if (chain.books.size() <= 1) {
			return ActionResult.CONSUME;
		}

		// Rotate contents of area immediately above valid targets
		var previousVolume = new TeleportVolume();
		{
			var loc = chain.books.get(chain.books.size() - 1);

			previousVolume.cut(server.getWorld(loc.world), loc.center, loc.forward);
		}
		for (var loc : chain.books) {
			var world2 = server.getWorld(loc.world);

			var currentVolume = new TeleportVolume();
			currentVolume.cut(world2, loc.center, loc.forward);

			previousVolume.paste(world2, loc.center, loc.forward);
			previousVolume = currentVolume;
		}

		return ActionResult.CONSUME;
	}

	@Override
	public void onInitializeServer() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (player.isSpectator()) {
				return ActionResult.PASS;
			}
			if (hand != Hand.MAIN_HAND || hitResult.getType() != HitResult.Type.BLOCK) {
				return ActionResult.PASS;
			}

			var blockPos = hitResult.getBlockPos();
			var blockState = world.getBlockState(blockPos);

			if (!blockState.isOf(Blocks.LECTERN)) {
				return ActionResult.PASS;
			}

			return handleLecternUse((ServerWorld) world, blockPos);
		});
	}
}