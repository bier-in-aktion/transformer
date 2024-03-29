package transformer;

import com.example.app.Product;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public class ProductTransformer {

    static final String TOPIC_PRODUCTS_RAW = "products";
    static final String TOPIC_PRODUCTS_UPDATES = "products-updates";
    static final String STATE_STORE  = "products-state";

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "product-transformer");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:29092");
        props.put("schema.registry.url", "http://localhost:8081");


        final Map<String, String> serdeConfig = Collections.singletonMap("schema.registry.url", "http://localhost:8081");
        final Serde<Product> productSerde = new SpecificAvroSerde<>();
        productSerde.configure(serdeConfig, false);

        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, productSerde.getClass().getName());

        StoreBuilder<KeyValueStore<String, Product>> keyValueStoreBuilder =
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE),
                Serdes.String(),
                productSerde);

        final StreamsBuilder builder = new StreamsBuilder();

        builder.addStateStore(keyValueStoreBuilder);

        builder.<String, Product>stream(TOPIC_PRODUCTS_RAW)
            .process(ProductTransformer::hasDiscountPercentageChangedTransformer, Named.as("product-process"), STATE_STORE)
            .filter((key, value) -> value != null)
            .to(TOPIC_PRODUCTS_UPDATES, Produced.with(Serdes.String(), productSerde));

        final Topology topology = builder.build();
        final KafkaStreams streams = new KafkaStreams(topology, props);

        streams.start();

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    private static boolean hasDiscountPercentageChanged(Product oldProduct, Product newProduct) {
        if (oldProduct == null || newProduct == null) {
            return true;
        }

        Integer oldDiscount = oldProduct.getPrice().getDiscountPercentage();
        Integer newDiscount = newProduct.getPrice().getDiscountPercentage();

        return !Objects.equals(oldDiscount, newDiscount);
    }

    private static Processor<String, Product, String, Product> hasDiscountPercentageChangedTransformer() {
        return new Processor<String, Product, String, Product>() {
            private KeyValueStore<String, Product> stateStore;
            private ProcessorContext context;

            @Override
            public void init(ProcessorContext context) {
                this.context = context;
                this.stateStore = context.getStateStore(STATE_STORE);
            }

            @Override
            public void process(Record<String, Product> record) {

                Product currentState = stateStore.get(record.key());
                if (currentState == null || hasDiscountPercentageChanged(currentState, record.value())) {
                    stateStore.put(record.key(), record.value());
                    context.forward(record);
                }
            }
        };
    }
}
