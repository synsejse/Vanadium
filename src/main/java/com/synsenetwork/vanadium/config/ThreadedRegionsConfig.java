package com.synsenetwork.vanadium.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import com.synsenetwork.vanadium.parallelised.threads.ThreadedChunksRegion;

import java.util.ArrayList;
import java.util.List;

@Config(name = "vanadium-threaded-regions")
public class ThreadedRegionsConfig implements ConfigData {
    public List<ThreadedChunksRegion> threadedChunksRegions = new ArrayList<>();
}
