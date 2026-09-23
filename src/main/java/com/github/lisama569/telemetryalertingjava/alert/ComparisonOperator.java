package com.github.lisama569.telemetryalertingjava.alert;

/**
 * 规则阈值比较算子：实际值 actual 与阈值 threshold 比较，true 表示"超标（breached）"。
 */
public enum ComparisonOperator {
    GT {
        @Override
        public boolean test(double actual, double threshold) {
            return actual > threshold;
        }

        @Override
        public String render(double actual, double threshold) {
            return actual + " > " + threshold;
        }
    },
    GTE {
        @Override
        public boolean test(double actual, double threshold) {
            return actual >= threshold;
        }

        @Override
        public String render(double actual, double threshold) {
            return actual + " >= " + threshold;
        }
    },
    LT {
        @Override
        public boolean test(double actual, double threshold) {
            return actual < threshold;
        }

        @Override
        public String render(double actual, double threshold) {
            return actual + " < " + threshold;
        }
    },
    LTE {
        @Override
        public boolean test(double actual, double threshold) {
            return actual <= threshold;
        }

        @Override
        public String render(double actual, double threshold) {
            return actual + " <= " + threshold;
        }
    };

    public abstract boolean test(double actual, double threshold);

    /** 渲染判定依据，用于日志与评估结果。 */
    public abstract String render(double actual, double threshold);
}
