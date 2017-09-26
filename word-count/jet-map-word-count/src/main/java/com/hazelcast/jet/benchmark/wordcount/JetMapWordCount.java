package com.hazelcast.jet.benchmark.wordcount;


import com.hazelcast.jet.Jet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Vertex;

import java.util.Map;
import java.util.StringTokenizer;

import static com.hazelcast.jet.aggregate.AggregateOperations.counting;
import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.Partitioner.HASH_CODE;
import static com.hazelcast.jet.core.processor.Processors.accumulateByKey;
import static com.hazelcast.jet.core.processor.Processors.combineByKey;
import static com.hazelcast.jet.core.processor.Processors.flatMap;
import static com.hazelcast.jet.core.processor.SinkProcessors.writeMap;
import static com.hazelcast.jet.core.processor.SourceProcessors.readMap;
import static com.hazelcast.jet.function.DistributedFunctions.entryKey;
import static com.hazelcast.jet.function.DistributedFunctions.wholeItem;

public class JetMapWordCount {

    public static void main(String[] args) throws Exception {
        JetInstance client = Jet.newJetClient();

        String sourceMap = args[0];
        String sinkMap = args[1];

        DAG dag = new DAG();

        Vertex producer = dag.newVertex("reader", readMap(sourceMap)).localParallelism(3);

        Vertex tokenizer = dag.newVertex("tokenizer",
                flatMap((Map.Entry<?, String> entry) -> {
                    StringTokenizer s = new StringTokenizer(entry.getValue());
                    return () -> s.hasMoreTokens() ? s.nextToken() : null;
                })
        );

        // word -> (word, count)
        Vertex accumulate = dag.newVertex("accumulate", accumulateByKey(wholeItem(), counting()));

        // (word, count) -> (word, count)
        Vertex combine = dag.newVertex("combine", combineByKey(counting()));
        Vertex consumer = dag.newVertex("writer", writeMap(sinkMap)).localParallelism(1);

        dag.edge(between(producer, tokenizer))
           .edge(between(tokenizer, accumulate)
                   .partitioned(wholeItem(), HASH_CODE))
           .edge(between(accumulate, combine)
                   .distributed()
                   .partitioned(entryKey()))
           .edge(between(combine, consumer));

        JobConfig config = new JobConfig();
        config.addClass(JetMapWordCount.class);

        try {
            long start = System.currentTimeMillis();
            client.newJob(dag, config).execute().get();
            System.out.println("Time=" + (System.currentTimeMillis() - start));

        } finally {
            client.shutdown();
        }
    }
}
