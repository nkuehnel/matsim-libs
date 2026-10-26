package org.matsim.contrib.discrete_mode_choice.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.contribs.discrete_mode_choice.components.estimators.pmml.PmmlTripContext;
import org.matsim.contribs.discrete_mode_choice.components.estimators.pmml.PmmlVariableProvider;
import org.matsim.contribs.discrete_mode_choice.modules.DiscreteModeChoiceConfigurator;
import org.matsim.contribs.discrete_mode_choice.modules.DiscreteModeChoiceModule;
import org.matsim.contribs.discrete_mode_choice.modules.EstimatorModule;
import org.matsim.contribs.discrete_mode_choice.modules.ModeAvailabilityModule;
import org.matsim.contribs.discrete_mode_choice.modules.ModelModule.ModelType;
import org.matsim.contribs.discrete_mode_choice.modules.SelectorModule;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.contribs.discrete_mode_choice.modules.config.PmmlTripEstimatorConfigGroup;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.testcases.MatsimTestUtils;

import com.google.inject.multibindings.Multibinder;

/**
 * Mode choice in the loop on equil with a car/walk MNL read from PMML.
 */
public class TestPmmlEquil {
	@RegisterExtension
	public final MatsimTestUtils utils = new MatsimTestUtils();

	private Config config(String pmmlFile) {
		URL scenarioURL = ExamplesUtils.getTestScenarioURL("equil");
		Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioURL, "config.xml"),
				new DiscreteModeChoiceConfigGroup());
		DiscreteModeChoiceConfigurator.configureAsModeChoiceInTheLoop(config);

		DiscreteModeChoiceConfigGroup dmcConfig = (DiscreteModeChoiceConfigGroup) config.getModules()
				.get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
		dmcConfig.setModelType(ModelType.Trip);
		dmcConfig.setModeAvailability(ModeAvailabilityModule.DEFAULT);
		dmcConfig.getDefaultModeAvailabilityConfig().setAvailableModes(List.of("car", "walk"));
		dmcConfig.setTripEstimator(EstimatorModule.PMML);
		dmcConfig.setSelector(SelectorModule.MULTINOMIAL_LOGIT);
		dmcConfig.setCachedModes(Set.of());
		dmcConfig.getPmmlTripEstimatorConfigGroup().setPmmlFile(pmmlFile);

		config.controller().setOutputDirectory(utils.getOutputDirectory());
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(2);
		config.controller().setCreateGraphs(false);
		config.controller().setWritePlansInterval(0);
		config.controller().setWriteEventsInterval(0);
		config.controller().setDumpDataAtEnd(false);
		return config;
	}

	private static String resource(String name) {
		try {
			return new File(TestPmmlEquil.class.getResource("/pmml/" + name).toURI()).getAbsolutePath();
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	void testModeChoiceWithPmmlModel() {
		Config config = config(resource("equil-mnl.pmml"));
		Controler controller = new Controler(config);
		controller.addOverridingModule(new DiscreteModeChoiceModule());

		Map<String, Integer> departures = new HashMap<>();
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance((PersonDepartureEventHandler) (PersonDepartureEvent event) -> {
					if (event.getLegMode().equals("car") || event.getLegMode().equals("walk")) {
						departures.merge(event.getLegMode(), 1, Integer::sum);
					}
				});
			}
		});
		controller.run();

		// the short work-to-work trips are walked by some agents, the long ones are driven
		assertTrue(departures.getOrDefault("car", 0) > 0, departures.toString());
		assertTrue(departures.getOrDefault("walk", 0) > 0, departures.toString());
	}

	@Test
	void testIncompleteSetupFailsAtStartup() {
		// the model expects walk.tt in minutes; a provider that reports seconds must stop the run before iteration 0
		Config config = config(resource("equil-mnl.pmml"));
		Controler controller = new Controler(config);
		controller.addOverridingModule(new DiscreteModeChoiceModule());
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				Multibinder.newSetBinder(binder(), PmmlVariableProvider.class).addBinding().toInstance(new SecondsProvider());
			}
		});
		RuntimeException e = assertThrows(RuntimeException.class, controller::run);
		Throwable cause = e;
		while (cause != null && !(cause instanceof PmmlException)) {
			cause = cause.getCause();
		}
		assertTrue(cause != null && cause.getMessage().contains("walk.tt"), String.valueOf(e));
	}

	@Test
	void testConfigRoundTrip() {
		Config config = config("model.pmml");
		PmmlTripEstimatorConfigGroup pmml = ((DiscreteModeChoiceConfigGroup) config.getModules()
				.get(DiscreteModeChoiceConfigGroup.GROUP_NAME)).getPmmlTripEstimatorConfigGroup();
		pmml.setModeMappingAsString("car:car, drt:pt");
		pmml.setVariableAliasesAsString("tt_car:car.tt");
		pmml.setParametersAsString("asc_pt:-0.42");

		String path = utils.getOutputDirectory() + "/config_pmml.xml";
		new ConfigWriter(config).write(path);
		DiscreteModeChoiceConfigGroup read = new DiscreteModeChoiceConfigGroup();
		ConfigUtils.loadConfig(path, read);

		PmmlTripEstimatorConfigGroup pmml2 = read.getPmmlTripEstimatorConfigGroup();
		assertEquals("model.pmml", pmml2.getPmmlFile());
		assertEquals(Map.of("car", "car", "drt", "pt"), pmml2.getModeMapping());
		assertEquals(Map.of("tt_car", "car.tt"), pmml2.getVariableAliases());
		assertEquals(Map.of("asc_pt", -0.42), pmml2.getParameters());
		assertEquals(EstimatorModule.PMML, read.getTripEstimator());
	}

	static class SecondsProvider implements PmmlVariableProvider {
		@Override
		public boolean provides(String variable, String alternative) {
			return variable.equals("walk.tt");
		}

		@Override
		public Optional<String> getUnit(String variable) {
			return Optional.of("s");
		}

		@Override
		public double getValue(String variable, PmmlTripContext context) {
			return context.getRouteSummary().mainTravelTime_s();
		}
	}
}
