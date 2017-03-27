package com.emc.mongoose.run.scenario.step;

import com.emc.mongoose.run.scenario.ScenarioParseException;
import com.emc.mongoose.ui.config.Config;
import com.emc.mongoose.ui.log.Markers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 Created by kurila on 02.02.16.
 */
public class SequentialStep
extends ParentStepBase {
	//
	private static final Logger LOG = LogManager.getLogger();
	//
	public SequentialStep(final Config appConfig, final Map<String, Object> subTree)
	throws ScenarioParseException {
		super(appConfig, subTree);
	}
	//
	@Override
	public String toString() {
		return "sequentialJob#" + hashCode();
	}
	//
	@Override
	public synchronized void run() {
		super.run();
		LOG.info(
			Markers.MSG, "{}: execute {} child jobs sequentially", toString(), subSteps.size()
		);
		for(final Step subStep : subSteps) {
			LOG.debug(Markers.MSG, "{}: child job \"{}\" start", toString(), subStep.toString());
			subStep.run();
			LOG.debug(Markers.MSG, "{}: child job \"{}\" is done", toString(), subStep.toString());
		}
		LOG.info(Markers.MSG, "{}: finished the sequential execution of {} child jobs", toString());
	}
}