package com.netease.nim.camellia.tools.statistic;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Created by caojiajun on 2022/7/21
 */
public class CamelliaStatistics {

    private final LongAdder count = new LongAdder();
    private final LongAdder sum = new LongAdder();
    private final MaxValue maxValue = new MaxValue();

    private final AtomicLong[] distribute;

    public CamelliaStatistics(int expectedMaxValue) {
        distribute = new AtomicLong[expectedMaxValue];
        for (int i=0; i<expectedMaxValue; i++) {
            distribute[i] = new AtomicLong(0);
        }
    }

    public CamelliaStatistics() {
        this(5000);
    }

    public void update(long value) {
        count.increment();
        sum.add(value);
        maxValue.update(value);
        if (value < 0) return;
        AtomicLong distributeCounter;
        if (value >= distribute.length) {
            distributeCounter = distribute[distribute.length - 1];
        } else {
            distributeCounter = distribute[(int) value];
        }
        if (distributeCounter != null) {
            distributeCounter.incrementAndGet();
        }
    }

    public CamelliaStatsData getStatsDataAndReset() {
        long sum = this.sum.sumThenReset();
        long count = this.count.sumThenReset();
        long max = this.maxValue.getAndSet(0);
        double avg = (double) sum / count;
        long c = 0;
        long p50Position = (long) (count * 0.5);
        long p75Position = (long) (count * 0.75);
        long p90Position = (long) (count * 0.90);
        long p95Position = (long) (count * 0.95);
        long p99Position = (long) (count * 0.99);
        long p999Position = (long) (count * 0.999);
        long p50 = -1;
        long p75 = -1;
        long p90 = -1;
        long p95 = -1;
        long p99 = -1;
        long p999 = -1;
        long lastIndexNum = distribute[distribute.length - 1].get();
        for (int i=0; i < distribute.length; i++) {
            c += distribute[i].getAndSet(0);
            if (p50 == -1 && c >= p50Position) {
                p50 = i;
            }
            if (p75 == -1 && c >= p75Position) {
                p75 = i;
            }
            if (p90 == -1 && c >= p90Position) {
                p90 = i;
            }
            if (p95 == -1 && c >= p95Position) {
                p95 = i;
            }
            if (p99 == -1 && c >= p99Position) {
                p99 = i;
            }
            if (p999 == -1 && c >= p999Position) {
                p999 = i;
            }
        }
        if (p50 == distribute.length - 1) {
            p50 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p50Position);
        }
        if (p75 == distribute.length - 1) {
            p75 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p75Position);
        }
        if (p90 == distribute.length - 1) {
            p90 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p90Position);
        }
        if (p95 == distribute.length - 1) {
            p95 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p95Position);
        }
        if (p99 == distribute.length - 1) {
            p99 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p99Position);
        }
        if (p999 == distribute.length - 1) {
            p999 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p999Position);
        }
        return new CamelliaStatsData(count, avg, max, sum, p50, p75, p90, p95, p99, p999);
    }

    private long quantileExceed(long max, int maxIndex, long lastIndexNum, long count, long quantilePosition) {
        return Math.round(max - ((max - maxIndex + 1) / (lastIndexNum * 1.0)) * (count - quantilePosition));
    }

    public CamelliaStatsData getStatsData() {
        long sum = this.sum.sum();
        long count = this.count.sum();
        long max = maxValue.get();
        double avg = (double) sum / count;
        long c = 0;
        long p50Position = (long) (count * 0.5);
        long p75Position = (long) (count * 0.75);
        long p90Position = (long) (count * 0.90);
        long p95Position = (long) (count * 0.95);
        long p99Position = (long) (count * 0.99);
        long p999Position = (long) (count * 0.999);
        long p50 = -1;
        long p75 = -1;
        long p90 = -1;
        long p95 = -1;
        long p99 = -1;
        long p999 = -1;
        long lastIndexNum = distribute[distribute.length - 1].get();
        for (int i=0; i < distribute.length; i++) {
            c += distribute[i].get();
            if (p50 == -1 && c >= p50Position) {
                p50 = i;
            }
            if (p75 == -1 && c >= p75Position) {
                p75 = i;
            }
            if (p90 == -1 && c >= p90Position) {
                p90 = i;
            }
            if (p95 == -1 && c >= p95Position) {
                p95 = i;
            }
            if (p99 == -1 && c >= p99Position) {
                p99 = i;
            }
            if (p999 == -1 && c >= p999Position) {
                p999 = i;
            }
        }
        if (p50 == distribute.length - 1) {
            p50 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p50Position);
        }
        if (p75 == distribute.length - 1) {
            p75 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p75Position);
        }
        if (p90 == distribute.length - 1) {
            p90 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p90Position);
        }
        if (p95 == distribute.length - 1) {
            p95 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p95Position);
        }
        if (p99 == distribute.length - 1) {
            p99 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p99Position);
        }
        if (p999 == distribute.length - 1) {
            p999 = quantileExceed(max, distribute.length - 1, lastIndexNum, count, p999Position);
        }
        return new CamelliaStatsData(count, avg, max, sum, p50, p75, p90, p95, p99, p999);
    }

    private static class MaxValue {
        private final AtomicLong max = new AtomicLong(0L);

        public void update(long value) {
            while (true) {
                long oldValue = max.get();
                if (value > oldValue) {
                    if (max.compareAndSet(oldValue, value)) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        public long getAndSet(long newValue) {
            return max.getAndSet(newValue);
        }

        public long get() {
            return max.get();
        }
    }
}
