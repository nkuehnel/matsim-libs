package org.matsim.contribs.discrete_mode_choice.components.estimators.pmml;

import java.util.Optional;

/**
 * Provides values for the inputs (variables) of a PMML choice model during
 * trip estimation. Built-in variables come from
 * {@link DefaultPmmlVariableProvider}; scenario-specific ones (costs, fares,
 * parking, waiting times of on-demand services, ...) are added by binding own
 * providers:
 *
 * <pre>
 * Multibinder.newSetBinder(binder(), PmmlVariableProvider.class).addBinding().to(MyCostProvider.class);
 * </pre>
 *
 * Bound providers take precedence over the default provider. Two bound
 * providers for the same variable are an error.
 *
 * @author nkuehnel
 */
public interface PmmlVariableProvider {
	/**
	 * Whether this provider can compute {@code variable} when the utility of
	 * {@code alternative} (name in the PMML file) is evaluated. Called once per
	 * variable and alternative when the estimator is created.
	 */
	boolean provides(String variable, String alternative);

	/**
	 * Unit of the value returned for {@code variable}, e.g. "min", "km" or
	 * "EUR". If both the provider and the PMML file declare a unit, they must be
	 * equal; otherwise the estimator refuses to start.
	 */
	default Optional<String> getUnit(String variable) {
		return Optional.empty();
	}

	double getValue(String variable, PmmlTripContext context);
}
