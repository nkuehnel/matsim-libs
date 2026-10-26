package org.matsim.contribs.discrete_mode_choice.components.estimators.pmml;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.components.estimators.AbstractTripRouterEstimator;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlChoiceModel;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlException;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlVariable;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;

/**
 * Trip estimator whose utilities come from a choice model in a PMML file, e.g.
 * a multinomial logit estimated with Apollo and exported with apollo2pmml.
 * <p>
 * Every MATSim mode is evaluated as one alternative of the model
 * ({@code modeMapping}, by default the alternative of the same name). For each
 * mode only the inputs that the utility of its alternative refers to are
 * filled, by the bound {@link PmmlVariableProvider}s or, if none of them
 * provides an input, by the {@link DefaultPmmlVariableProvider}. All inputs are
 * resolved and their units checked against the PMML file when the estimator is
 * created, so an incomplete or inconsistent setup fails before the simulation
 * runs.
 * <p>
 * The estimator returns the systematic utility. Random terms (e.g. frozen
 * Gumbel errors) and selection belong to the DMC selector.
 *
 * @author nkuehnel
 */
public class PmmlTripEstimator extends AbstractTripRouterEstimator {
	private static final Logger logger = LogManager.getLogger(PmmlTripEstimator.class);

	private record Input(String input, String variable, PmmlVariableProvider provider) {
	}

	private record ModeBinding(String alternative, int alternativeIndex, Input[] inputs) {
	}

	private final PmmlChoiceModel model;
	private final Map<String, ModeBinding> modes;

	/**
	 * @param modeMapping     MATSim mode to PMML alternative; empty: identity for
	 *                        all alternatives
	 * @param variableAliases PMML input to provider variable; inputs without alias
	 *                        keep their name
	 * @param providers       custom providers, consulted before the default one
	 */
	public PmmlTripEstimator(TripRouter tripRouter, ActivityFacilities facilities, TimeInterpretation timeInterpretation,
			Collection<String> preroutedModes, PmmlChoiceModel model, Map<String, String> modeMapping,
			Map<String, String> variableAliases, Collection<? extends PmmlVariableProvider> providers) {
		super(tripRouter, facilities, timeInterpretation, preroutedModes);
		this.model = model;
		this.modes = bind(model, modeMapping, variableAliases, List.copyOf(providers), new DefaultPmmlVariableProvider());
	}

	@Override
	protected double estimateTrip(Person person, String mode, DiscreteModeChoiceTrip trip,
			List<TripCandidate> previousTrips, List<? extends PlanElement> routedTrip) {
		ModeBinding binding = modes.get(mode);
		if (binding == null) {
			throw new IllegalStateException(String.format(
					"Mode '%s' is not an alternative of the PMML model (alternatives: %s, mapped modes: %s); add it to modeMapping",
					mode, model.getAlternatives(), modes.keySet()));
		}
		PmmlTripContext context = new PmmlTripContext(person, mode, binding.alternative(), trip, routedTrip,
				previousTrips);
		PmmlChoiceModel.Inputs inputs = model.newInputs();
		for (Input input : binding.inputs()) {
			inputs.set(input.input(), input.provider().getValue(input.variable(), context));
		}
		return model.getUtility(binding.alternativeIndex(), inputs);
	}

	/** MATSim modes this estimator can evaluate */
	public Collection<String> getModes() {
		return Collections.unmodifiableSet(modes.keySet());
	}

	private static Map<String, ModeBinding> bind(PmmlChoiceModel model, Map<String, String> modeMapping,
			Map<String, String> variableAliases, List<PmmlVariableProvider> providers,
			PmmlVariableProvider defaultProvider) {
		if (!model.hasUtilities()) {
			throw new PmmlException("The PMML model has no utility(<alternative>) outputs; it cannot be used for trip estimation");
		}
		for (String input : variableAliases.keySet()) {
			if (model.getVariable(input).isEmpty()) {
				throw new PmmlException("variableAliases: '" + input + "' is not an input of the PMML model");
			}
		}

		Map<String, String> mapping = new LinkedHashMap<>(modeMapping);
		if (mapping.isEmpty()) {
			model.getAlternatives().forEach(a -> mapping.put(a, a));
		}

		Map<String, ModeBinding> bindings = new HashMap<>();
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, String> entry : mapping.entrySet()) {
			String mode = entry.getKey();
			String alternative = entry.getValue();
			int index = model.getAlternatives().indexOf(alternative);
			if (index < 0) {
				problems.add(String.format("mode '%s' is mapped to '%s', which is not an alternative (%s)", mode,
						alternative, model.getAlternatives()));
				continue;
			}

			List<Input> inputs = new ArrayList<>();
			for (String input : model.getUtilityInputs(alternative)) {
				String variable = variableAliases.getOrDefault(input, input);
				Optional<PmmlVariableProvider> provider = findProvider(variable, alternative, providers, defaultProvider,
						problems);
				if (provider.isEmpty()) {
					continue;
				}
				checkUnit(model.getVariable(input).get(), variable, provider.get(), problems);
				inputs.add(new Input(input, variable, provider.get()));
			}
			bindings.put(mode, new ModeBinding(alternative, index, inputs.toArray(Input[]::new)));
		}

		if (!problems.isEmpty()) {
			throw new PmmlException("The PMML model cannot be evaluated in this scenario:\n  " + String.join("\n  ", problems));
		}
		return bindings;
	}

	private static Optional<PmmlVariableProvider> findProvider(String variable, String alternative,
			List<PmmlVariableProvider> providers, PmmlVariableProvider defaultProvider, List<String> problems) {
		List<PmmlVariableProvider> candidates = providers.stream().filter(p -> p.provides(variable, alternative))
				.toList();
		if (candidates.size() > 1) {
			problems.add(String.format("variable '%s' (alternative '%s') is provided by more than one provider: %s",
					variable, alternative, candidates.stream().map(p -> p.getClass().getName()).toList()));
			return Optional.empty();
		}
		if (candidates.size() == 1) {
			return Optional.of(candidates.get(0));
		}
		if (defaultProvider.provides(variable, alternative)) {
			return Optional.of(defaultProvider);
		}
		problems.add(String.format("no provider for variable '%s' (alternative '%s'); bind a PmmlVariableProvider or set variableAliases",
				variable, alternative));
		return Optional.empty();
	}

	private static void checkUnit(PmmlVariable input, String variable, PmmlVariableProvider provider,
			List<String> problems) {
		Optional<String> expected = input.unit();
		Optional<String> actual = provider.getUnit(variable);
		if (expected.isPresent() && actual.isPresent()
				&& !expected.get().trim().equalsIgnoreCase(actual.get().trim())) {
			problems.add(String.format("input '%s' has unit '%s' in the PMML file, but '%s' provides '%s'", input.name(),
					expected.get(), variable, actual.get()));
		} else if (expected.isPresent() && actual.isEmpty()) {
			logger.warn("Unit of '{}' ({}) cannot be checked: '{}' has no declared unit", input.name(), expected.get(),
					variable);
		}
	}
}
