package com.mentlerd;

import com.google.common.collect.Lists;
import net.fabricmc.api.DedicatedServerModInitializer;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LecternBlock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Clearable;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.ModifiableWorld;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class Telebooks implements DedicatedServerModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("telebooks");

	static class VolumeSnapshot {
		static class BlockInfo {
			public Vec3i offset;
			public BlockState state;
			public NbtCompound blockEntityData;
		}

		private final ArrayList<BlockInfo> blocks = new ArrayList<>();

		public void copy(WorldAccess world, BlockPos center, Vec3i mins, Vec3i maxs, Direction forward) {
			var right = forward.rotateYClockwise();

			var rotation = switch (forward) {
				case UP, DOWN -> throw new IllegalArgumentException();

				case NORTH -> BlockRotation.NONE;
				case EAST -> BlockRotation.COUNTERCLOCKWISE_90;
				case SOUTH -> BlockRotation.CLOCKWISE_180;
				case WEST -> BlockRotation.CLOCKWISE_90;
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
		}

		public void paste(World world, BlockPos center, Direction forward) {
			var right = forward.rotateYClockwise();

			var rotation = switch (forward) {
				case UP, DOWN -> throw new IllegalArgumentException();

				case NORTH -> BlockRotation.NONE;
				case EAST -> BlockRotation.CLOCKWISE_90;
				case SOUTH -> BlockRotation.CLOCKWISE_180;
				case WEST -> BlockRotation.COUNTERCLOCKWISE_90;
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
		}
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

			var lecterns = Lists.newArrayList(
					new BlockPos(-36, -60, -1),
					new BlockPos(-42, -60, -1),
					new BlockPos(-46, -60, 3),
					new BlockPos(-46, -60, 9),
					new BlockPos(-42, -60, 13),
					new BlockPos(-36, -60, 13),
					new BlockPos(-32, -60, 9),
					new BlockPos(-32, -60, 3)
			);

			var mins = new Vec3i(-1, -1, -1);
			var maxs = new Vec3i(1, 1, 1);

			VolumeSnapshot prev = null;

			for (int index = 0; index <= lecterns.size(); index++) {
				var lectern = lecterns.get(index % lecterns.size());

				var forward = world.getBlockState(lectern).get(LecternBlock.FACING);
				var center = lectern.offset(forward, 2).offset(Direction.UP);

				var snapshot = new VolumeSnapshot();
				snapshot.copy(world, center, mins, maxs, forward);

				if (prev != null) {
					prev.paste(world, center, forward);
				}

				prev = snapshot;
			}

			return ActionResult.PASS;
		});
	}
}