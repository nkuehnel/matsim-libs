# Estimator

An `Estimator` component has the purpose to analyze a certain propose mode (in a trip-based model) or a chain of modes (in a tour-based model) and assign a certain utility to it. Later on, this utility can be used to make an educated guess about which alternative should be chosen.

The DMC extension contains a number of predefined `Estimator` implementations, but it is also possible to write custom ones. How to do that is explained in [Customizing the model](../Customizing.md).

In the following the existing built-in estimators are described. Some of them exist in a trip-based and tour-based version, some only in one. While some of them have additional configuration options that can be defined in a `parameterset`, some don't. In any case, the estimator can be chosen in the main config group. For a trip-based model, the estimator can be defined as `tripEstimator`, while for a tour-based model it needs to be set in the `tourEstimator` parameter. Depending on which [model](Model.md) type is used, the respective field is active:

```xml
<module name="DiscreteModeChoice">
	<!-- Defines which TourEstimator component to use. Built-in choices: ... -->
	<param name="tourEstimator" value="Cumulative" />
	<!-- Defines which TripEstimator component to use. Built-in choices: ... -->
	<param name="tripEstimator" value="Uniform" />
	<!-- Trips tested with the modes listed here will be cached for each combination of trip and agent during one replanning pass. -->
	<param name="cachedModes" value="car, pt, ..." />
</module>
```

The `cachedModes` parameter defines which trip estimates should be cached. This is especially useful in a tour-based set-up. If estimates are not cached, trips are re-routed for each possible tour. If they are cached, one and the same trip (with the same mode) reuses one estimate in any tour that it is part of.

## Cumulative

*Description:* The `Cumulative` tour estimator is a special estimator that does not perform any estimation on its own. Instead if look up the estimator given in `tripEstimator` and applies it to each trip in a tour independently. Finally, the utilities of the single trips are summed up.

*Level:* Tour

*Configuration:*
No specific configuration available. The `tripEstimator` field from the main DMC configuration is used.

## MATSimTripScoring

*Description:* The `MATSimTripScoring` trip estimator approximates the MATSim scoring function. Internally, the considered trip is routed using MATSim's `TripRouter` component. Afterwards, the scoring parameters defined in the respective `calcScore` config group are applied. Since the at the time of replanning the exact resulting departure and travel time is not known completely, this is only an approximation. 

*Level:* Trip

*Configuration:*
No specific configuration is available. The parameters from `calcScore` are used.

## PMML

*Description:* The `PMML` trip estimator takes its utilities from a discrete choice model stored as [PMML 4.4](https://dmg.org/pmml/v4-4-1/GeneralStructure.html), for example a multinomial logit estimated with [Apollo](https://www.apollochoicemodelling.com) and exported with [apollo2pmml](https://github.com/nkuehnel/apollo2pmml). The PMML file is evaluated by a small built-in evaluator (JDK only, no third-party library). A new model is a new file; no Java code changes as long as its inputs can be provided.

Every MATSim mode is evaluated as one alternative of the model. For each mode only the inputs that the utility of its alternative refers to are filled. Inputs are named after a variable contract, and the default provider knows these:

| Variable | Unit | Value |
|---|---|---|
| `person.<name>` | | person attribute `<name>` (number or boolean) |
| `trip.beeline_km` | km | beeline distance of the trip |
| `trip.departure_h` | h | departure time |
| `trip.<name>` | | trip attribute `<name>` |
| `<alt>.tt` | min | travel time of the main legs (in-vehicle time for pt) |
| `<alt>.wait` | min | waiting time before boarding (pt) |
| `<alt>.access` | min | access, egress and transfer walks |
| `<alt>.total_tt` | min | door-to-door travel time |
| `<alt>.distance_km` | km | distance of the main legs |
| `<alt>.transfers` | | number of main legs minus one |
| `<alt>.av`, `<alt>.hyp` | | 1 and 0 (availability is decided by DMC, simulated connections are real) |

Scenario-specific inputs (costs, fares, parking, on-demand waiting times) come from own providers, which take precedence over the default one:

```java
Multibinder.newSetBinder(binder(), PmmlVariableProvider.class).addBinding().to(MyCostProvider.class);
```

All inputs are resolved when the simulation starts. A missing provider, two providers for the same input or a unit that differs from the `unit` extension in the PMML file stops the run with a list of all problems; missing person or trip attributes are errors, never silent zeros.

*Level:* Trip

*Configuration:*

```xml
<parameterset type="tripEstimator:PMML">
	<!-- PMML file of the choice model (relative to the config file). -->
	<param name="pmmlFile" value="mode_choice_v3.pmml" />
	<!-- MATSim mode to PMML alternative. Empty: every alternative is the MATSim mode of the same name. -->
	<param name="modeMapping" value="car:car, ride:ride, pt:pt, bike:bike, walk:walk, drt:pt" />
	<!-- PMML input to provider variable. Inputs without alias keep their name. -->
	<param name="variableAliases" value="tt_car:car.tt, distance_km:trip.beeline_km" />
	<!-- Coefficients replaced by other values, e.g. calibrated constants. Empty: the estimates in the file. -->
	<param name="parameters" value="asc_pt:-0.42" />
</parameterset>
```

The estimator returns the systematic utility; use the `MultinomialLogit` selector, or frozen errors with the `Maximum` selector, for the choice. Supported is the "choice-model profile" written by apollo2pmml: a classification `RegressionModel` (`simplemax`, `softmax` or `none`) with derived fields built from `Constant`, `FieldRef` and `Apply` with the PMML built-in arithmetic, comparison, logical, `if`, `exp`, `ln`, `pow`, `sqrt`, `abs`, `min`/`max` and similar functions. Anything else is rejected when the file is read. Nested logit (reading the nest structure for the nested logit selector) is planned.

## MATSimDayScoring

*Description:* The `MATSimDayScoring` tour estimator approximates the MATSim scoring function, similar to the `MATSimTripScoring` esimator, on which it builds. Two improvement are included that make use of the tour-based character: Using the initial departure time of the tour and the estimated travel times, delays can be predicted and considered to certain extent. Also, day-based scoring parameters such as daily costs are considered in this estimator. Again, it only approximates the score accumulated by trips throughout the considered day, but *not* the score resulting from activities. *In principle, this would be possible, so here we consider it as future work*. Note that day-based scoring parameters are considered. Therefore, the estimator should mainly be used in a plan-based context (except those parameters can be expected to be zero).

*Level:* Tour (preferrable plan-based setup)

*Configuration:*
No specific configuration is available. The parameters from `calcScore` are used.

## Uniform

*Description:* The `Uniform` selector returns `1.0` for any alternative that it encounters. Therefore, each altenratives obtaines the same valuation.

*Level:* Tour or Trip

*Configuration:*
No specific configuration is available.