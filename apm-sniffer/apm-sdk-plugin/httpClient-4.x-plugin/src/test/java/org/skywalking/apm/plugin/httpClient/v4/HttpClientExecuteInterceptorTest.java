package org.skywalking.apm.plugin.httpClient.v4;

import java.lang.reflect.Field;
import org.apache.http.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.skywalking.apm.agent.core.boot.ServiceManager;
import org.skywalking.apm.agent.core.context.TracerContext;
import org.skywalking.apm.agent.core.plugin.interceptor.EnhancedClassInstanceContext;
import org.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodInvokeContext;
import org.skywalking.apm.sniffer.mock.context.MockTracerContextListener;
import org.skywalking.apm.sniffer.mock.context.SegmentAssert;
import org.skywalking.apm.sniffer.mock.trace.tags.BooleanTagReader;
import org.skywalking.apm.sniffer.mock.trace.tags.StringTagReader;
import org.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.skywalking.apm.agent.core.context.tag.Tags;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest(HttpHost.class)
public class HttpClientExecuteInterceptorTest {

    private HttpClientExecuteInterceptor httpClientExecuteInterceptor;
    private MockTracerContextListener mockTracerContextListener;
    @Mock
    private EnhancedClassInstanceContext classInstanceContext;
    @Mock
    private InstanceMethodInvokeContext instanceMethodInvokeContext;
    @Mock
    private HttpHost httpHost;
    @Mock
    private HttpRequest request;
    @Mock
    private HttpResponse httpResponse;
    @Mock
    private StatusLine statusLine;

    @Before
    public void setUp() throws Exception {
        mockTracerContextListener = new MockTracerContextListener();

        ServiceManager.INSTANCE.boot();
        httpClientExecuteInterceptor = new HttpClientExecuteInterceptor();

        PowerMockito.mock(HttpHost.class);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(instanceMethodInvokeContext.allArguments()).thenReturn(new Object[] {httpHost, request});
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(httpHost.getHostName()).thenReturn("127.0.0.1");
        when(httpHost.getSchemeName()).thenReturn("http");
        when(request.getRequestLine()).thenReturn(new RequestLine() {
            @Override
            public String getMethod() {
                return "GET";
            }

            @Override
            public ProtocolVersion getProtocolVersion() {
                return new ProtocolVersion("http", 1, 1);
            }

            @Override
            public String getUri() {
                return "http://127.0.0.1:8080/test-web/test";
            }
        });
        when(httpHost.getPort()).thenReturn(8080);

        TracerContext.ListenerManager.add(mockTracerContextListener);
    }

    @Test
    public void testHttpClient() {
        httpClientExecuteInterceptor.beforeMethod(classInstanceContext, instanceMethodInvokeContext, null);
        httpClientExecuteInterceptor.afterMethod(classInstanceContext, instanceMethodInvokeContext, httpResponse);

        mockTracerContextListener.assertSize(1);
        mockTracerContextListener.assertTraceSegment(0, new SegmentAssert() {
            @Override
            public void call(TraceSegment traceSegment) {
                assertThat(traceSegment.getSpans().size(), is(1));
                assertHttpSpan(traceSegment.getSpans().get(0));
                verify(request, times(1)).setHeader(anyString(), anyString());
            }
        });
    }

    @Test
    public void testStatusCodeNotEquals200() {
        when(statusLine.getStatusCode()).thenReturn(500);
        httpClientExecuteInterceptor.beforeMethod(classInstanceContext, instanceMethodInvokeContext, null);
        httpClientExecuteInterceptor.afterMethod(classInstanceContext, instanceMethodInvokeContext, httpResponse);

        mockTracerContextListener.assertSize(1);
        mockTracerContextListener.assertTraceSegment(0, new SegmentAssert() {
            @Override
            public void call(TraceSegment traceSegment) {
                assertThat(traceSegment.getSpans().size(), is(1));
                assertHttpSpan(traceSegment.getSpans().get(0));
                assertThat(BooleanTagReader.get(traceSegment.getSpans().get(0), Tags.ERROR), is(true));
                verify(request, times(1)).setHeader(anyString(), anyString());
            }
        });
    }

    @Test
    public void testHttpClientWithException() {
        httpClientExecuteInterceptor.beforeMethod(classInstanceContext, instanceMethodInvokeContext, null);
        httpClientExecuteInterceptor.handleMethodException(new RuntimeException(), classInstanceContext, instanceMethodInvokeContext);
        httpClientExecuteInterceptor.afterMethod(classInstanceContext, instanceMethodInvokeContext, httpResponse);

        mockTracerContextListener.assertSize(1);
        mockTracerContextListener.assertTraceSegment(0, new SegmentAssert() {
            @Override
            public void call(TraceSegment traceSegment) {
                assertThat(traceSegment.getSpans().size(), is(1));
                Span span = traceSegment.getSpans().get(0);
                assertHttpSpan(span);
                assertThat(BooleanTagReader.get(span, Tags.ERROR), is(true));
                try {
                    assertHttpSpanErrorLog(getLogs(span));
                } catch (NoSuchFieldException e) {
                    throw new RuntimeException(e);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                verify(request, times(1)).setHeader(anyString(), anyString());

            }

            private void assertHttpSpanErrorLog(List<LogData> logs) {
                assertThat(logs.size(), is(1));
                LogData logData = logs.get(0);
                assertThat(logData.getFields().size(), is(4));
            }
        });

    }

    private void assertHttpSpan(Span span) {
        assertThat(span.getOperationName(), is("/test-web/test"));
        assertThat(StringTagReader.get(span, Tags.COMPONENT), is("HttpClient"));
        assertThat(span.getPeerHost(), is("127.0.0.1"));
        assertThat(span.getPort(), is(8080));
        assertThat(StringTagReader.get(span, Tags.URL), is("http://127.0.0.1:8080/test-web/test"));
        assertThat(StringTagReader.get(span, Tags.SPAN_KIND), is(Tags.SPAN_KIND_CLIENT));
    }

    @After
    public void tearDown() throws Exception {
        TracerContext.ListenerManager.remove(mockTracerContextListener);
    }

    protected List<LogData> getLogs(Span span) throws NoSuchFieldException, IllegalAccessException {
        Field logs = Span.class.getDeclaredField("logs");
        logs.setAccessible(true);
        return (List<LogData>)logs.get(span);
    }

}
