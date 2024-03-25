package com.mentlerd;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.api.DedicatedServerModInitializer;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.*;
import net.minecraft.util.dynamic.Codecs;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

	static class VolumeSnapshot {
		static class BlockInfo {
			public Vec3i offset;
			public BlockState state;
			public NbtCompound blockEntityData;
		}
		static class EntityInfo {
			public Vec3d offset;
			public EntityType<?> type;
			public NbtCompound entityData;
		}

		private final ArrayList<BlockInfo> blocks = new ArrayList<>();
		private final ArrayList<EntityInfo> entities = new ArrayList<>();

		public void copy(WorldAccess world, BlockPos center, Vec3i mins, Vec3i maxs, Direction forward) {
			var right = forward.rotateYClockwise();

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

						var info = new BlockInfo();

						info.offset = new Vec3i(offX, offY, offZ);
						info.state = world.getBlockState(pos).rotate(rotation);

						var blockEntity = world.getBlockEntity(pos);
						if (blockEntity != null) {
							info.blockEntityData = blockEntity.createNbt();
						}

						blocks.add(info);
					}
				}
			}

			var centerPos = center.toCenterPos();

			var entityBox = new Box(
				centerPos.add(mins.getX(), mins.getY(), mins.getZ()),
				centerPos.add(maxs.getX(), maxs.getY(), maxs.getZ())
			);

			for (var entity : world.getNonSpectatingEntities(Entity.class, entityBox)) {
				var type = entity.getType();

				if (!type.isSaveable() || !type.isSummonable()) {
					continue;
				}

				var info = new EntityInfo();

				info.offset = rotateOffset(entity.getPos().subtract(centerPos), rotation);
				info.type = type;
				info.entityData = new NbtCompound();

				if (!entity.saveNbt(info.entityData)) {
					LOGGER.warn("Failed to copy entity");
					continue;
				}

				entities.add(info);
			}
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

			var centerPos = center.toCenterPos();

			for (var info : entities) {
				var pos = centerPos.add(rotateOffset(info.offset, rotation));

				var posList = new NbtList();
				posList.add(NbtDouble.of(pos.getX()));
				posList.add(NbtDouble.of(pos.getY()));
				posList.add(NbtDouble.of(pos.getZ()));

				var spawnData = info.entityData.copy();
				spawnData.put("Pos", posList);
				spawnData.remove("UUID");

				EntityType.getEntityFromNbt(spawnData, world).ifPresent(entity -> {
					entity.refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), entity.getYaw() + entity.applyRotation(rotation), entity.getPitch());

					world.spawnEntityAndPassengers(entity);
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

		private BlockPos localToGlobal(BlockPos pos) {
			return pos.rotate(rotationFrom).add(origin);
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
				var state = transformed.getBlockState(offX, 0, offZ);

				pattern.add(state);
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
	}

	static class State extends PersistentState {
		private static final Type<State> type = new Type<>(State::new, nbt -> {
			var value = new State();
			value.readNbt(nbt);
			return value;
		}, null);

		public static State get(MinecraftServer server) {
			var world = Objects.requireNonNull(server.getWorld(World.OVERWORLD));

			var state = world.getPersistentStateManager().getOrCreate(type, MOD_ID);

			// NB: Technically this is incorrect, but saving happens rather rarely, and it is much more
			//  convenient to just assume that the state has changed.
			state.markDirty();

			return state;
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
		var forward = world.getBlockState(pos).get(LecternBlock.FACING);
		var center = pos.offset(forward, 2).offset(Direction.DOWN);

		// Check whether this lectern sits on top of a valid book pattern
		var pattern = tryGetBookPattern(world, center, forward);
		if (pattern.isEmpty()) {
			return ActionResult.PASS;
		}

		// If so, locate corresponding book chain
		var state = State.get(world.getServer());
		var chain = state.getOrCreateBookChain(pattern.get());

		// Check whether this book is a new joiner, and add them to the chain
		{
			var book = new BookLocation(world.getRegistryKey(), center, forward);

			if (!chain.books.contains(book)) {
				chain.books.add(book);
			}
		}

		// Check target locations to see which ones still have the pattern intact
		var targets = chain.books.stream().filter(loc -> {
			var world2 = world.getServer().getWorld(loc.world);

			var candidatePattern = tryGetBookPattern(world2, loc.center, loc.forward);
			if (candidatePattern.isEmpty()) {
				return false;
			}

			return pattern.equals(candidatePattern);
		}).toList();

		// We might not have enough books intact to perform teleportation
		if (targets.size() <= 1) {
			return ActionResult.CONSUME;
		}

		// Rotate contents of area immediately above valid targets
		LOGGER.info("Will rotate: {}", targets);

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