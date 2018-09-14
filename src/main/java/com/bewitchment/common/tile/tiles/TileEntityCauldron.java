package com.bewitchment.common.tile.tiles;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.bewitchment.api.mp.IMagicPowerConsumer;
import com.bewitchment.common.Bewitchment;
import com.bewitchment.common.content.cauldron.behaviours.DefaultBehaviours;
import com.bewitchment.common.content.cauldron.behaviours.ICauldronBehaviour;
import com.bewitchment.common.core.helper.ColorHelper;
import com.bewitchment.common.core.helper.Log;
import com.bewitchment.common.tile.ModTileEntity;
import com.bewitchment.common.tile.util.CauldronFluidTank;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import net.minecraft.util.NonNullList;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

public class TileEntityCauldron extends ModTileEntity implements ITickable {

	public static final int DEFAULT_COLOR = 0x42499b;

	private NonNullList<ItemStack> ingredients = NonNullList.create();
	private AxisAlignedBB collectionZone;
	private CauldronFluidTank tank;
	
	private IMagicPowerConsumer powerManager = IMagicPowerConsumer.CAPABILITY.getDefaultInstance();
	
	private DefaultBehaviours defaultBehaviours = new DefaultBehaviours();
	private LinkedList<ICauldronBehaviour> decorators = new LinkedList<>();
	private ICauldronBehaviour currentBehaviour;

	private String name;

	private int targetColorRGB = DEFAULT_COLOR;
	private int effectiveClientSideColor = DEFAULT_COLOR;

	public TileEntityCauldron() {
		collectionZone = new AxisAlignedBB(0, 0, 0, 1, 0.65D, 1);
		tank = new CauldronFluidTank(this);
		defaultBehaviours.init(this);
	}

	@Override
	public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		if (playerIn.isSneaking() && playerIn.getHeldItem(hand).isEmpty()) {
			worldIn.setBlockState(pos, state.cycleProperty(Bewitchment.HALF), 3);
		}
		ItemStack heldItem = playerIn.getHeldItem(hand);
		if (!playerIn.world.isRemote) {
			if (ingredients.size() == 0 && heldItem.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null)) {
				FluidUtil.interactWithFluidHandler(playerIn, hand, tank);
				if (playerIn.isCreative() && tank.isFull()) {
					markDirty();
					syncToClient();
				}
			} 
		}
		
		currentBehaviour.playerInteract(playerIn, hand);
		
		return true;
	}

	@Override
	public void update() {
		if (world.isRemote) {
			if (effectiveClientSideColor != targetColorRGB) {
				effectiveClientSideColor = ColorHelper.blendColor(effectiveClientSideColor, targetColorRGB, 0.92f);
			}
		} else {
			decorators.forEach(d -> d.update(d==currentBehaviour));
			if (decorators.stream().allMatch(d -> !d.shouldInputsBeBlocked())) {
				ItemStack next = gatherNextItemFromTop();
				if (!next.isEmpty()) {
					ingredients.add(next);
					setTankLock(false);
					decorators.forEach(d -> d.statusChanged(d == currentBehaviour));
					if (targetColorRGB != currentBehaviour.getColor()) {
						setColor(currentBehaviour.getColor());
					}
					markDirty();
					syncToClient();
				}
			}
		}
	}

	public NonNullList<ItemStack> getInputs() {
		return ingredients;
	}



	public void addBehaviour(ICauldronBehaviour b) {
		decorators.add(b);
	}

	private ItemStack gatherNextItemFromTop() {
		List<EntityItem> list = world.getEntitiesWithinAABB(EntityItem.class, collectionZone.offset(getPos()));
		if (list.isEmpty()) {
			return ItemStack.EMPTY;
		}
		EntityItem selectedEntityItem = list.get(0);
		if (currentBehaviour.canAccept(selectedEntityItem)) {
			ItemStack next = selectedEntityItem.getItem().splitStack(1);
			if (selectedEntityItem.getItem().isEmpty()) {
				selectedEntityItem.setDead();
				world.playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 1f, (float) (0.2f*Math.random()+1));
			}
			return next;
		}
		return ItemStack.EMPTY;
	}

	public int getColorRGB() {
		return effectiveClientSideColor;
	}
	
	public boolean hasItemsInside() {
		return !ingredients.isEmpty();
	}

	public Optional<FluidStack> getFluid() {
		return tank.isEmpty() ? Optional.empty() : Optional.ofNullable(tank.getFluid());
	}

	public void setColor(int color) {
		targetColorRGB = color;
		syncToClient();
		markDirty();
	}

	public static void giveItemToPlayer(EntityPlayer player, ItemStack toGive) {
		if (!player.inventory.addItemStackToInventory(toGive)) {
			player.dropItem(toGive, false);
		} else if (player instanceof EntityPlayerMP) {
			((EntityPlayerMP) player).sendContainerToPlayer(player.inventoryContainer);
		}
	}
	
	public String getName() {
		return name;
	}

	@Override
	public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
		return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || capability == IMagicPowerConsumer.CAPABILITY;
	}

	@Nullable
	@Override
	public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
		if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(tank);
		}
		if (capability == IMagicPowerConsumer.CAPABILITY) {
			return IMagicPowerConsumer.CAPABILITY.cast(powerManager);
		}
		return super.getCapability(capability, facing);
	}
	
	public void handleParticles() {
		decorators.forEach(d -> d.handleParticles(d == currentBehaviour));
	}

	@Override
	protected void writeAllModDataNBT(NBTTagCompound tag) {
		tag.setInteger("color", targetColorRGB);
		tag.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		tag.setTag("ingredients", ItemStackHelper.saveAllItems(new NBTTagCompound(), ingredients));
		if (name != null) {
			tag.setString("name", name);
		}
		decorators.forEach(d -> d.saveToNBT(tag));
		tag.setString("behaviour", currentBehaviour.getID());
		tag.setTag("mp", powerManager.writeToNbt());
	}

	@Override
	protected void readAllModDataNBT(NBTTagCompound tag) {
		targetColorRGB = tag.getInteger("color");
		tank.readFromNBT(tag.getCompoundTag("tank"));
		ingredients.clear();
		if (tag.hasKey("name")) {
			name = tag.getString("name");
		} else {
			name = null;
		}
		ItemStackHelper.loadAllItems(tag.getCompoundTag("ingredients"), ingredients);
		decorators.forEach(d -> d.loadFromNBT(tag));
		String id = tag.getString("behaviour");
		currentBehaviour = decorators.stream().filter(d -> d.getID().equals(id)).findFirst().orElse(defaultBehaviours.IDLE);
		powerManager.readFromNbt(tag.getCompoundTag("mp"));
	}

	@Override
	protected void writeModSyncDataNBT(NBTTagCompound tag) {
		tag.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		tag.setInteger("color", targetColorRGB);
		tag.setBoolean("hasItemsInside", ingredients.size() > 0);
		if (name != null) {
			tag.setString("name", name);
		}
		decorators.forEach(d -> d.saveToSyncNBT(tag));
		tag.setString("behaviour", currentBehaviour.getID());
	}

	@Override
	protected void readModSyncDataNBT(NBTTagCompound tag) {
		tank.readFromNBT(tag.getCompoundTag("tank"));
		targetColorRGB = tag.getInteger("color");
		if (tag.getBoolean("hasItemsInside")) {
			ingredients.clear();
			ingredients.add(ItemStack.EMPTY); // Makes the list not empty
		}
		if (tag.hasKey("name")) {
			name = tag.getString("name");
		} else {
			name = null;
		}
		String id = tag.getString("behaviour");
		currentBehaviour = decorators.stream().filter(d -> d.getID().equals(id)).findFirst().orElse(defaultBehaviours.IDLE);
		decorators.forEach(d -> d.loadFromSyncNBT(tag));
	}

	public void clearItemInputs() {
		ingredients.clear();
		decorators.forEach(d -> d.statusChanged(d==currentBehaviour));
		markDirty();
		syncToClient();
	}
	
	public void setTankLock(boolean canTransfer) {
		tank.setCanDrain(canTransfer);
		tank.setCanFill(canTransfer);
		markDirty();
	}

	public void setBehaviour(ICauldronBehaviour behaviour) {
		if (behaviour == null) {
			Log.w("null behaviour decorator for cauldron!");
			Log.askForReport();
			setBehaviour(defaultBehaviours.IDLE);
		} else if (behaviour != currentBehaviour) {
			if (currentBehaviour != null) {
				currentBehaviour.onDeactivation();
			}
			currentBehaviour = behaviour;
			setColor(currentBehaviour.getColor());
			markDirty();
			syncToClient();
		}
	}

	public void onLiquidChange() {
		decorators.forEach(d -> d.statusChanged(d == currentBehaviour));
		markDirty();
		syncToClient();
	}

	
	public void clearTanks() {
		tank.setFluid(null);
		setTankLock(true);
		syncToClient();
	}

	public DefaultBehaviours getDefaultBehaviours() {
		return defaultBehaviours;
	}

	public ICauldronBehaviour getCurrentBehaviour() {
		return currentBehaviour;
	}
}
