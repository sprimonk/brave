/*
 * Copyright 2013-2023 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.kafka.streams;

import brave.Span;
import brave.Tracer;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;

import static brave.internal.Throwables.propagateIfFatal;

/*
 * Note. the V2 naming convention has been introduced here to help distinguish between the existing TracingProcessor classes
 * and those that implement the new kafka streams API introduced in version 3.4.0
 */
class TracingV2Processor<KIn, VIn, KOut, VOut> implements Processor<KIn, VIn, KOut, VOut> {
  final KafkaStreamsTracing kafkaStreamsTracing;
  final Tracer tracer;
  final String spanName;
  final Processor<KIn, VIn, KOut, VOut> delegateProcessor;

  ProcessorContext processorContext;

  TracingV2Processor(KafkaStreamsTracing kafkaStreamsTracing,
                     String spanName, Processor<KIn, VIn, KOut, VOut> delegateProcessor) {
    this.kafkaStreamsTracing = kafkaStreamsTracing;
    this.tracer = kafkaStreamsTracing.tracer;
    this.spanName = spanName;
    this.delegateProcessor = delegateProcessor;
  }

  @Override
  public void init(ProcessorContext<KOut, VOut> context) {
    this.processorContext = context;
    delegateProcessor.init(processorContext);
  }

  @Override
  public void process(Record<KIn, VIn> record) {
    Span span = kafkaStreamsTracing.nextSpan(processorContext, record.headers());
    if (!span.isNoop()) {
      span.name(spanName);
      span.start();
    }

    Tracer.SpanInScope ws = tracer.withSpanInScope(span);
    Throwable error = null;
    try {
      delegateProcessor.process(record);
    } catch (Throwable e) {
      error = e;
      propagateIfFatal(e);
      throw e;
    } finally {
      // Inject this span so that the next stage uses it as a parent
      kafkaStreamsTracing.injector.inject(span.context(), record.headers());
      if (error != null) span.error(error);
      span.finish();
      ws.close();
    }
  }

  @Override
  public void close() {
    delegateProcessor.close();
  }
}
