package com.mwang;

import com.mwang.sim.SimulationConfig;
import com.mwang.sim.SimulationRunner;

import java.io.IOException;

/**
 * Entry point. Everything interesting is in {@link SimulationRunner}; this only chooses the
 * configuration to run it with.
 */
public final class Main {

    public static void main(String[] args) throws IOException, InterruptedException {
        new SimulationRunner(SimulationConfig.defaults()).run();
    }
}
