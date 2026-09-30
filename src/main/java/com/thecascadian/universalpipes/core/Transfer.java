package com.thecascadian.universalpipes.core;

import com.thecascadian.universalpipes.filter.Expr;
import com.thecascadian.universalpipes.filter.FilterSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The transfer engine. Every move is simulated on the destination first, then
 * extracted for exactly the accepted amount, then inserted for real. Anything a
 * destination refuses after extraction goes back to the source, and only when
 * the source refuses it too is it dropped as an item or reported as a loss, so
 * nothing is voided or duplicated silently.
 */
public final class Transfer {

    private static final int FULL_PERCENT = 100;

    /** A destination with lazily created capability caches, valid while its topology is current. */
    public static final class Dest {
        private final PipeNetworks.Destination destination;
        private final BooleanSupplier valid;
        private final Runnable wake;
        private BlockCapabilityCache<IItemHandler, Direction> items;
        private BlockCapabilityCache<IFluidHandler, Direction> fluids;
        private BlockCapabilityCache<IEnergyStorage, Direction> energy;

        public Dest(PipeNetworks.Destination destination, BooleanSupplier valid, Runnable wake) {
            this.destination = destination;
            this.valid = valid;
            this.wake = wake;
        }

        public PipeNetworks.Destination destination() {
            return destination;
        }

        public BlockPos target() {
            return destination.target();
        }

        private Direction side() {
            return destination.face().getOpposite();
        }

        IItemHandler items(ServerLevel level) {
            if (items == null)
                items = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, target(), side(), valid, wake);
            IItemHandler handler = items.getCapability();
            return handler != null ? handler : Targets.fallbackItems(level, target(), side());
        }

        IFluidHandler fluids(ServerLevel level) {
            if (fluids == null)
                fluids = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, target(), side(), valid,
                        wake);
            IFluidHandler handler = fluids.getCapability();
            return handler != null ? handler : Targets.fallbackFluids(level, target(), side());
        }

        IEnergyStorage energy(ServerLevel level) {
            if (energy == null)
                energy = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, target(), side(), valid,
                        wake);
            IEnergyStorage handler = energy.getCapability();
            return handler != null ? handler : Targets.fallbackEnergy(level, target(), side());
        }
    }

    /** Mutable result of one attempt, reduced to a Status by the caller. */
    public static final class Tally {
        public int moved;
        public boolean filtered;
        public boolean full;
        public boolean unloaded;
        public boolean lost;
    }

    private Transfer() {
    }

    public static void items(ServerLevel level, BlockPos sourcePos, IItemHandler source,
            EndpointConfig.Transport settings, int budget, List<Dest> order, Tally tally) {
        for (int slot = 0; slot < source.getSlots() && budget > 0; slot++) {
            ItemStack probe = source.extractItem(slot, budget, true);
            if (probe.isEmpty() || probe.is(TransportType.NON_TRANSFERABLE_ITEM))
                continue;
            if (settings.keepInSource() > 0) {
                int spare = count(source, probe) - settings.keepInSource();
                if (spare <= 0)
                    continue;
                probe = probe.copyWithCount(Math.min(probe.getCount(), spare));
            }
            Expr.Subject subject = Expr.subject(probe);
            int remaining = probe.getCount();
            for (Dest dest : order) {
                if (remaining <= 0 || budget <= 0)
                    break;
                IItemHandler target = dest.items(level);
                if (target == null) {
                    tally.unloaded |= !level.isLoaded(dest.target());
                    continue;
                }
                int cap = settings.filter().limitFor(subject, dest.target());
                if (cap == FilterSet.DENIED) {
                    tally.filtered = true;
                    continue;
                }
                int amount = Math.min(Math.min(remaining, cap), budget);
                if (settings.stopAtDestination() > 0)
                    amount = Math.min(amount, settings.stopAtDestination() - count(target, probe));
                if (amount <= 0) {
                    tally.full = true;
                    continue;
                }
                int accepted = amount - ItemHandlerHelper.insertItem(target, probe.copyWithCount(amount), true)
                        .getCount();
                if (accepted <= 0) {
                    tally.full = true;
                    continue;
                }
                ItemStack taken = source.extractItem(slot, accepted, false);
                if (taken.isEmpty())
                    break;
                ItemStack left = ItemHandlerHelper.insertItem(target, taken, false);
                int delivered = taken.getCount() - left.getCount();
                if (!left.isEmpty())
                    left = ItemHandlerHelper.insertItem(source, left, false);
                if (!left.isEmpty()) {
                    Block.popResource(level, sourcePos, left);
                    tally.lost = true;
                }
                tally.moved += delivered;
                budget -= taken.getCount();
                remaining -= taken.getCount();
            }
        }
    }

    public static void fluids(ServerLevel level, IFluidHandler source, EndpointConfig.Transport settings, int budget,
            List<Dest> order, Tally tally) {
        for (int tank = 0; tank < source.getTanks() && budget > 0; tank++) {
            FluidStack inTank = source.getFluidInTank(tank);
            if (inTank.isEmpty() || inTank.getFluid().is(TransportType.NON_TRANSFERABLE_FLUID))
                continue;
            FluidStack probe = source.drain(inTank.copyWithAmount(Math.min(budget, inTank.getAmount())),
                    IFluidHandler.FluidAction.SIMULATE);
            if (probe.isEmpty())
                continue;
            if (settings.keepInSource() > 0) {
                int spare = amount(source, probe) - settings.keepInSource();
                if (spare <= 0)
                    continue;
                probe = probe.copyWithAmount(Math.min(probe.getAmount(), spare));
            }
            Expr.Subject subject = Expr.subject(probe);
            int remaining = probe.getAmount();
            for (Dest dest : order) {
                if (remaining <= 0 || budget <= 0)
                    break;
                IFluidHandler target = dest.fluids(level);
                if (target == null) {
                    tally.unloaded |= !level.isLoaded(dest.target());
                    continue;
                }
                int cap = settings.filter().limitFor(subject, dest.target());
                if (cap == FilterSet.DENIED) {
                    tally.filtered = true;
                    continue;
                }
                int wanted = Math.min(Math.min(remaining, cap), budget);
                if (settings.stopAtDestination() > 0)
                    wanted = Math.min(wanted, settings.stopAtDestination() - amount(target, probe));
                if (wanted <= 0) {
                    tally.full = true;
                    continue;
                }
                int accepted = target.fill(probe.copyWithAmount(wanted), IFluidHandler.FluidAction.SIMULATE);
                if (accepted <= 0) {
                    tally.full = true;
                    continue;
                }
                FluidStack drained = source.drain(probe.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE);
                if (drained.isEmpty())
                    break;
                int filled = target.fill(drained, IFluidHandler.FluidAction.EXECUTE);
                int stray = drained.getAmount() - filled;
                if (stray > 0
                        && source.fill(drained.copyWithAmount(stray), IFluidHandler.FluidAction.EXECUTE) < stray)
                    tally.lost = true;
                tally.moved += filled;
                budget -= drained.getAmount();
                remaining -= drained.getAmount();
            }
        }
    }

    public static void energy(ServerLevel level, IEnergyStorage source, EndpointConfig.Transport settings, int budget,
            List<Dest> order, Tally tally) {
        int available = source.extractEnergy(budget, true);
        if (settings.keepInSource() > 0) {
            int floor = percentOf(source.getMaxEnergyStored(), settings.keepInSource());
            available = Math.min(available, source.getEnergyStored() - floor);
        }
        for (Dest dest : order) {
            if (available <= 0)
                break;
            IEnergyStorage target = dest.energy(level);
            if (target == null) {
                tally.unloaded |= !level.isLoaded(dest.target());
                continue;
            }
            int wanted = available;
            if (settings.stopAtDestination() > 0)
                wanted = Math.min(wanted,
                        percentOf(target.getMaxEnergyStored(), settings.stopAtDestination()) - target.getEnergyStored());
            if (wanted <= 0) {
                tally.full = true;
                continue;
            }
            int accepted = target.receiveEnergy(wanted, true);
            if (accepted <= 0) {
                tally.full = true;
                continue;
            }
            int extracted = source.extractEnergy(accepted, false);
            if (extracted <= 0)
                break;
            int received = target.receiveEnergy(extracted, false);
            int stray = extracted - received;
            if (stray > 0 && source.receiveEnergy(stray, false) < stray)
                tally.lost = true;
            tally.moved += received;
            available -= extracted;
        }
    }

    private static int percentOf(int maximum, int percent) {
        return (int) ((long) maximum * Math.min(percent, FULL_PERCENT) / FULL_PERCENT);
    }

    private static int count(IItemHandler handler, ItemStack like) {
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (ItemStack.isSameItemSameComponents(stack, like))
                total += stack.getCount();
        }
        return total;
    }

    private static int amount(IFluidHandler handler, FluidStack like) {
        int total = 0;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack stack = handler.getFluidInTank(tank);
            if (FluidStack.isSameFluidSameComponents(stack, like))
                total += stack.getAmount();
        }
        return total;
    }
}
