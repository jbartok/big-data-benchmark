/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.benchmark.trademonitor;

import org.apache.commons.lang.mutable.MutableLong;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.java.tuple.Tuple5;
import org.apache.flink.core.fs.FileSystem.WriteMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.WindowAssigner;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.streaming.util.serialization.AbstractDeserializationSchema;
import org.apache.flink.streaming.util.serialization.DeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.common.serialization.LongDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Properties;
import java.util.UUID;


public class FlinkTradeMonitor {

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            System.err.println("Usage:");
            System.err.println("  " + FlinkTradeMonitor.class.getSimpleName() +
                    " <bootstrap.servers> <topic> <offset-reset> <maxLagMs> <windowSizeMs> <slideByMs> <outputPath> <checkpointInt>");
            System.exit(1);
        }
        String brokerUri = args[0];
        String topic = args[1];
        String offsetReset = args[2];
        int lagMs = Integer.parseInt(args[3]);
        int windowSize = Integer.parseInt(args[4]);
        int slideBy = Integer.parseInt(args[5]);
        String outputPath = args[6];
        int checkpointInt = Integer.parseInt(args[7]);

        // set up the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        if (checkpointInt > 0) {
            env.enableCheckpointing(checkpointInt);
        }
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(Integer.MAX_VALUE, 5000));

        DeserializationSchema<Trade> schema = new AbstractDeserializationSchema<Trade>() {
            TradeDeserializer deserializer = new TradeDeserializer();

            @Override
            public Trade deserialize(byte[] message) throws IOException {
                return deserializer.deserialize(null, message);
            }
        };

        DataStreamSource<Trade> trades = env.addSource(new FlinkKafkaConsumer010<>(topic,
                schema, getKafkaProperties(brokerUri, offsetReset))).setParallelism(2);
        AssignerWithPeriodicWatermarks<Trade> timestampExtractor
                = new BoundedOutOfOrdernessTimestampExtractor<Trade>(Time.milliseconds(lagMs)) {
            @Override
            public long extractTimestamp(Trade element) {
                return element.getTime();
            }
        };

        WindowAssigner window = windowSize == slideBy ?
                TumblingEventTimeWindows.of(Time.milliseconds(windowSize)) :
                SlidingEventTimeWindows.of(Time.milliseconds(windowSize), Time.milliseconds(slideBy));

        trades
                .assignTimestampsAndWatermarks(timestampExtractor)
                .keyBy((Trade t) -> t.getTicker())
                .window(window)
                .aggregate(new AggregateFunction<Trade, MutableLong, Long>() {

                    @Override
                    public MutableLong createAccumulator() {
                        return new MutableLong();
                    }

                    @Override
                    public void add(Trade value, MutableLong accumulator) {
                        accumulator.increment();
                    }

                    @Override
                    public MutableLong merge(MutableLong a, MutableLong b) {
                        a.setValue(Math.addExact(a.longValue(), b.longValue()));
                        return a;
                    }

                    @Override
                    public Long getResult(MutableLong accumulator) {
                        return accumulator.longValue();
                    }
                }, new WindowFunction<Long, Tuple5<String, String, Long, Long, Long>, String, TimeWindow>() {
                    @Override
                    public void apply(String key, TimeWindow window, Iterable<Long> input, Collector<Tuple5<String, String, Long, Long, Long>> out) throws Exception {
                        long timeMs = System.currentTimeMillis();
                        long count = input.iterator().next();
                        long latencyMs = timeMs - window.getEnd();
                        out.collect(new Tuple5<>(Instant.ofEpochMilli(window.getEnd()).atZone(ZoneId.systemDefault()).toLocalTime().toString(), key, count, timeMs, latencyMs));
                    }
                })
                .setParallelism(2)
                .writeAsCsv(outputPath, WriteMode.OVERWRITE);

        env.execute("Trade Monitor Example");
    }

    private static Properties getKafkaProperties(String brokerUrl, String offsetReset) {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", brokerUrl);
        props.setProperty("group.id", UUID.randomUUID().toString());
        props.setProperty("key.deserializer", LongDeserializer.class.getName());
        props.setProperty("value.deserializer", TradeDeserializer.class.getName());
        props.setProperty("auto.offset.reset", offsetReset);
        props.setProperty("max.poll.records", "32768");
        return props;
    }
}
