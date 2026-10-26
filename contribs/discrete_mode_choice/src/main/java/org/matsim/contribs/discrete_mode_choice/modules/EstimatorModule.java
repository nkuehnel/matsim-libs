package org.matsim.contribs.discrete_mode_choice.modules;

import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.matsim.contribs.discrete_mode_choice.components.estimators.CumulativeTourEstimator;
import org.matsim.contribs.discrete_mode_choice.components.estimators.MATSimDayScoringEstimator;
import org.matsim.contribs.discrete_mode_choice.components.estimators.MATSimTripScoringEstimator;
import org.matsim.contribs.discrete_mode_choice.components.estimators.UniformTourEstimator;
import org.matsim.contribs.discrete_mode_choice.components.estimators.UniformTripEstimator;
import org.matsim.contribs.discrete_mode_choice.components.estimators.pmml.PmmlTripEstimator;
import org.matsim.contribs.discrete_mode_choice.components.estimators.pmml.PmmlVariableProvider;
import org.matsim.contribs.discrete_mode_choice.components.utils.NullWaitingTimeEstimator;
import org.matsim.contribs.discrete_mode_choice.components.utils.PTWaitingTimeEstimator;
import org.matsim.contribs.discrete_mode_choice.model.estimation.CachedTripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.contribs.discrete_mode_choice.modules.config.MATSimTripScoringConfigGroup;
import org.matsim.contribs.discrete_mode_choice.modules.config.PmmlTripEstimatorConfigGroup;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlChoiceModel;
import org.matsim.contribs.discrete_mode_choice.modules.utils.ScheduleWaitingTimeEstimatorModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.router.TripRouter;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.pt.config.TransitConfigGroup;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;

/**
 * Internal module that manages all built-in estimators.
 * 
 * @author sebhoerl
 *
 */
public class EstimatorModule extends AbstractDiscreteModeChoiceExtension {
	public static final String MATSIM_TRIP_SCORING = "MATSimTripScoring";
	public static final String MATSIM_DAY_SCORING = "MATSimDayScoring";
	public static final String CUMULATIVE = "Cumulative";
	public static final String UNIFORM = "Uniform";
	public static final String PMML = "PMML";

	public static final Collection<String> TRIP_COMPONENTS = Arrays.asList(MATSIM_TRIP_SCORING, UNIFORM, PMML);
	public static final Collection<String> TOUR_COMPONENTS = Arrays.asList(MATSIM_DAY_SCORING, CUMULATIVE, UNIFORM);

	@Override
	public void installExtension() {
		bindTripEstimator(MATSIM_TRIP_SCORING).to(MATSimTripScoringEstimator.class);
		bindTripEstimator(UNIFORM).to(UniformTripEstimator.class);
		bindTripEstimator(PMML).to(PmmlTripEstimator.class);
		Multibinder.newSetBinder(binder(), PmmlVariableProvider.class);

		bindTourEstimator(MATSIM_DAY_SCORING).to(MATSimDayScoringEstimator.class);
		bindTourEstimator(CUMULATIVE).to(CumulativeTourEstimator.class);
		bindTourEstimator(UNIFORM).to(UniformTourEstimator.class);

		if (getConfig().getModules().get(DiscreteModeChoiceConfigGroup.GROUP_NAME) instanceof DiscreteModeChoiceConfigGroup dmcConfig
				&& PMML.equals(dmcConfig.getTripEstimator())) {
			// resolve all PMML inputs at startup instead of in the first replanning
			addControllerListenerBinding().to(PmmlStartupCheck.class);
		}

		TransitConfigGroup transitConfigGroup = getConfig().transit();

		if (transitConfigGroup.isUseTransit()) {
			install(new ScheduleWaitingTimeEstimatorModule());
		} else {
			bind(PTWaitingTimeEstimator.class).to(NullWaitingTimeEstimator.class);
		}
	}

	@Provides
	public TourEstimator provideTourEstimator(DiscreteModeChoiceConfigGroup dmcConfig,
			Map<String, Provider<TourEstimator>> components) {
		Provider<TourEstimator> provider = components.get(dmcConfig.getTourEstimator());

		if (provider != null) {
			return provider.get();
		} else {
			throw new IllegalStateException(
					String.format("There is no TourEstimator component called '%s',", dmcConfig.getTourEstimator()));
		}
	}

	@Provides
	public TripEstimator provideTripEstimator(DiscreteModeChoiceConfigGroup dmcConfig,
			Map<String, Provider<TripEstimator>> components) {
		Provider<TripEstimator> provider = components.get(dmcConfig.getTripEstimator());

		if (provider != null) {
			return new CachedTripEstimator(provider.get(), dmcConfig.getCachedModes());
		} else {
			throw new IllegalStateException(
					String.format("There is no TripEstimator component called '%s',", dmcConfig.getTripEstimator()));
		}
	}

	@Provides
	@Singleton
	public UniformTripEstimator provideNullTripEstimator(TimeInterpretation timeInterpretation) {
		return new UniformTripEstimator(timeInterpretation);
	}

	@Provides
	@Singleton
	public UniformTourEstimator proideNullTourEstimator(TimeInterpretation timeInterpretation) {
		return new UniformTourEstimator(timeInterpretation);
	}

	@Provides
	@Singleton
	public NullWaitingTimeEstimator provideNullWaitingTimeEstimator() {
		return new NullWaitingTimeEstimator();
	}

	@Provides
	public MATSimTripScoringEstimator provideMATSimTripScoringEstimator(ActivityFacilities facilities,
			TripRouter tripRouter, PTWaitingTimeEstimator waitingTimeEstimator,
			ScoringParametersForPerson scoringParametersForPerson, DiscreteModeChoiceConfigGroup dmcConfig,
			TimeInterpretation timeInterpretation) {
		MATSimTripScoringConfigGroup scoringConfig = dmcConfig.getMATSimTripScoringConfigGroup();
		return new MATSimTripScoringEstimator(facilities, tripRouter, waitingTimeEstimator, scoringParametersForPerson,
				timeInterpretation, scoringConfig.getPtLegModes());
	}

	@Provides
	public MATSimDayScoringEstimator provideMATSimDayScoringEstimator(MATSimTripScoringEstimator tripEstimator,
			ScoringParametersForPerson scoringParametersForPerson, DiscreteModeChoiceConfigGroup dmcConfig,
			TimeInterpretation timeInterpretation) {
		return new MATSimDayScoringEstimator(new CachedTripEstimator(tripEstimator, dmcConfig.getCachedModes()),
				scoringParametersForPerson, timeInterpretation);
	}

	@Provides
	@Singleton
	public PmmlChoiceModel providePmmlChoiceModel(DiscreteModeChoiceConfigGroup dmcConfig, Config config) {
		PmmlTripEstimatorConfigGroup pmmlConfig = dmcConfig.getPmmlTripEstimatorConfigGroup();
		if (pmmlConfig.getPmmlFile() == null || pmmlConfig.getPmmlFile().isBlank()) {
			throw new IllegalStateException("The PMML trip estimator needs a pmmlFile");
		}
		URL url = ConfigGroup.getInputFileURL(config.getContext(), pmmlConfig.getPmmlFile());
		PmmlChoiceModel model = PmmlChoiceModel.read(url);
		return pmmlConfig.getParameters().isEmpty() ? model : model.withParameters(pmmlConfig.getParameters());
	}

	@Provides
	public PmmlTripEstimator providePmmlTripEstimator(ActivityFacilities facilities, TripRouter tripRouter,
			TimeInterpretation timeInterpretation, PmmlChoiceModel model, DiscreteModeChoiceConfigGroup dmcConfig,
			Set<PmmlVariableProvider> providers) {
		PmmlTripEstimatorConfigGroup pmmlConfig = dmcConfig.getPmmlTripEstimatorConfigGroup();
		return new PmmlTripEstimator(tripRouter, facilities, timeInterpretation, Collections.emptySet(), model,
				pmmlConfig.getModeMapping(), pmmlConfig.getVariableAliases(), providers);
	}

	static class PmmlStartupCheck implements StartupListener {
		private final Provider<PmmlTripEstimator> estimator;

		@Inject
		PmmlStartupCheck(Provider<PmmlTripEstimator> estimator) {
			this.estimator = estimator;
		}

		@Override
		public void notifyStartup(StartupEvent event) {
			estimator.get();
		}
	}

	@Provides
	public CumulativeTourEstimator provideCumulativeTourEstimator(TripEstimator tripEstimator,
			TimeInterpretation timeInterpretation) {
		return new CumulativeTourEstimator(tripEstimator, timeInterpretation);
	}
}
