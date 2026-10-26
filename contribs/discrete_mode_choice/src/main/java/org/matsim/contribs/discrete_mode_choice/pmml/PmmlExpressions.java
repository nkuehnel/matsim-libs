package org.matsim.contribs.discrete_mode_choice.pmml;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * Compiled PMML expressions. All values are doubles; booleans are 1.0 (true)
 * and 0.0 (false). Fields are slots of a {@link Frame}: model inputs first,
 * then derived fields, which are computed at most once per evaluation.
 *
 * @author nkuehnel
 */
final class PmmlExpressions {
	private PmmlExpressions() {
	}

	interface Expression {
		double evaluate(Frame frame);
	}

	/**
	 * Values of one evaluation. Not thread-safe; every evaluation uses its own
	 * frame (see {@link PmmlChoiceModel.Inputs}).
	 */
	static final class Frame {
		final double[] values;
		final boolean[] known;
		private final Expression[] derived;
		private final String[] names;
		private final int numberOfInputs;

		Frame(int numberOfInputs, Expression[] derived, String[] names) {
			this.numberOfInputs = numberOfInputs;
			this.derived = derived;
			this.names = names;
			this.values = new double[names.length];
			this.known = new boolean[names.length];
		}

		double get(int slot) {
			if (!known[slot]) {
				if (slot < numberOfInputs) {
					throw new PmmlException("Input '" + names[slot] + "' is not set");
				}
				values[slot] = derived[slot - numberOfInputs].evaluate(this);
				known[slot] = true;
			}
			return values[slot];
		}

		boolean isSet(int slot) {
			return known[slot];
		}

		/** forgets all derived values (inputs stay) */
		void resetDerived() {
			Arrays.fill(known, numberOfInputs, known.length, false);
		}
	}

	record Constant(double value) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			return value;
		}
	}

	record FieldRef(int slot) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			return frame.get(slot);
		}
	}

	/** an expression that fails when it is evaluated, e.g. a string-valued derived field */
	record Unsupported(String message) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			throw new PmmlException(message);
		}
	}

	record Unary(DoubleUnaryOperator operator, Expression argument) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			return operator.applyAsDouble(argument.evaluate(frame));
		}
	}

	record Binary(DoubleBinaryOperator operator, Expression left, Expression right) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			return operator.applyAsDouble(left.evaluate(frame), right.evaluate(frame));
		}
	}

	/** n-ary reduction (sum, product, min, max) */
	record Reduce(DoubleBinaryOperator operator, Expression[] arguments) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			double value = arguments[0].evaluate(frame);
			for (int i = 1; i < arguments.length; i++) {
				value = operator.applyAsDouble(value, arguments[i].evaluate(frame));
			}
			return value;
		}
	}

	record Average(Expression[] arguments) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			double sum = 0.0;
			for (Expression argument : arguments) {
				sum += argument.evaluate(frame);
			}
			return sum / arguments.length;
		}
	}

	/** if(condition, then[, else]): only the chosen branch is evaluated */
	record If(Expression condition, Expression whenTrue, Expression whenFalse) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			if (condition.evaluate(frame) != 0.0) {
				return whenTrue.evaluate(frame);
			}
			if (whenFalse == null) {
				throw new PmmlException("if() without else branch evaluated to a missing value");
			}
			return whenFalse.evaluate(frame);
		}
	}

	/** and / or with short-circuit evaluation */
	record Logical(boolean and, Expression[] arguments) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			for (Expression argument : arguments) {
				boolean value = argument.evaluate(frame) != 0.0;
				if (and && !value) {
					return 0.0;
				}
				if (!and && value) {
					return 1.0;
				}
			}
			return and ? 1.0 : 0.0;
		}
	}

	record IsMissing(int slot, boolean missing) implements Expression {
		@Override
		public double evaluate(Frame frame) {
			return frame.isSet(slot) != missing ? 1.0 : 0.0;
		}
	}

	private static double bool(boolean value) {
		return value ? 1.0 : 0.0;
	}

	static final Map<String, DoubleUnaryOperator> UNARY = Map.ofEntries( //
			Map.entry("exp", Math::exp), //
			Map.entry("ln", Math::log), //
			Map.entry("log10", Math::log10), //
			Map.entry("sqrt", Math::sqrt), //
			Map.entry("abs", Math::abs), //
			Map.entry("floor", Math::floor), //
			Map.entry("ceil", Math::ceil), //
			Map.entry("round", x -> (double) Math.round(x)), //
			Map.entry("not", x -> bool(x == 0.0)));

	static final Map<String, DoubleBinaryOperator> BINARY = Map.ofEntries( //
			Map.entry("+", (a, b) -> a + b), //
			Map.entry("-", (a, b) -> a - b), //
			Map.entry("*", (a, b) -> a * b), //
			Map.entry("/", (a, b) -> a / b), //
			Map.entry("pow", Math::pow), //
			Map.entry("threshold", (a, b) -> bool(a > b)), //
			Map.entry("equal", (a, b) -> bool(a == b)), //
			Map.entry("notEqual", (a, b) -> bool(a != b)), //
			Map.entry("lessThan", (a, b) -> bool(a < b)), //
			Map.entry("lessOrEqual", (a, b) -> bool(a <= b)), //
			Map.entry("greaterThan", (a, b) -> bool(a > b)), //
			Map.entry("greaterOrEqual", (a, b) -> bool(a >= b)));

	static final Map<String, DoubleBinaryOperator> REDUCE = Map.of( //
			"sum", (a, b) -> a + b, //
			"product", (a, b) -> a * b, //
			"min", Math::min, //
			"max", Math::max);

	static final List<String> SUPPORTED_FUNCTIONS = List.of("+", "-", "*", "/", "pow", "exp", "ln", "log10", "sqrt",
			"abs", "floor", "ceil", "round", "threshold", "min", "max", "sum", "product", "avg", "if", "and", "or", "not",
			"equal", "notEqual", "lessThan", "lessOrEqual", "greaterThan", "greaterOrEqual", "isMissing",
			"isNotMissing");
}
