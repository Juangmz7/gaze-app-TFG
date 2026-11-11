package telemetry

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"math"
	"strings"
	"testing"

	"github.com/ThreeDotsLabs/watermill/message"
	amqp "github.com/rabbitmq/amqp091-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/baggage"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.41.0"
	"go.opentelemetry.io/otel/trace"
)

// A trace ID whose low 8 bytes are all 0xff is never selected by
// TraceIDRatioBased for any ratio < 1.
var highTraceID = trace.TraceID{
	0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
	0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
}

func remoteParent(t *testing.T, sampled bool) context.Context {
	t.Helper()
	var flags trace.TraceFlags
	if sampled {
		flags = trace.FlagsSampled
	}
	sc := trace.NewSpanContext(trace.SpanContextConfig{
		TraceID:    highTraceID,
		SpanID:     trace.SpanID{0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11},
		TraceFlags: flags,
		Remote:     true,
	})
	return trace.ContextWithRemoteSpanContext(context.Background(), sc)
}

func TestSampler(t *testing.T) {
	tests := []struct {
		name   string
		parent func(*testing.T) context.Context
		ratio  float64
		want   sdktrace.SamplingDecision
	}{
		{
			name:   "sampled parent is kept even when the ratio would drop the trace",
			parent: func(t *testing.T) context.Context { return remoteParent(t, true) },
			ratio:  1e-9,
			want:   sdktrace.RecordAndSample,
		},
		{
			name:   "unsampled parent is dropped even with ratio 1",
			parent: func(t *testing.T) context.Context { return remoteParent(t, false) },
			ratio:  1,
			want:   sdktrace.Drop,
		},
		{
			name:   "root span uses the ratio (dropped)",
			parent: func(*testing.T) context.Context { return context.Background() },
			ratio:  1e-9,
			want:   sdktrace.Drop,
		},
		{
			name:   "root span uses the ratio (sampled)",
			parent: func(*testing.T) context.Context { return context.Background() },
			ratio:  1,
			want:   sdktrace.RecordAndSample,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := newSampler(tt.ratio).ShouldSample(sdktrace.SamplingParameters{
				ParentContext: tt.parent(t),
				TraceID:       highTraceID,
				Name:          "op",
				Kind:          trace.SpanKindConsumer,
			})
			if got.Decision != tt.want {
				t.Fatalf("decision = %v, want %v", got.Decision, tt.want)
			}
		})
	}
}

func TestSampler_TracerProviderContinuesSampledParent(t *testing.T) {
	tp := sdktrace.NewTracerProvider(sdktrace.WithSampler(newSampler(1e-9)))
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	_, span := tp.Tracer("test").Start(remoteParent(t, true), "child")
	defer span.End()

	sc := span.SpanContext()
	if !sc.IsSampled() {
		t.Fatal("child of a sampled remote parent was not sampled")
	}
	if sc.TraceID() != highTraceID {
		t.Fatalf("trace ID = %s, want parent's %s", sc.TraceID(), highTraceID)
	}
}

func TestResolveSamplingRatio(t *testing.T) {
	tests := []struct {
		name       string
		configured float64
		env        string
		want       float64
		wantErr    bool
	}{
		{name: "unset defaults to 1", want: 1},
		{name: "explicit value", configured: 0.25, want: 0.25},
		{name: "explicit 1", configured: 1, want: 1},
		{name: "explicit wins over env", configured: 0.5, env: "0.1", want: 0.5},
		{name: "env fallback", env: "0.1", want: 0.1},
		{name: "env blank is unset", env: "  ", want: 1},
		{name: "negative", configured: -0.5, wantErr: true},
		{name: "above 1", configured: 1.5, wantErr: true},
		{name: "NaN", configured: math.NaN(), wantErr: true},
		{name: "env zero", env: "0", wantErr: true},
		{name: "env above 1", env: "2", wantErr: true},
		{name: "env not a number", env: "half", wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv(EnvTracesSamplerArg, tt.env)
			got, err := resolveSamplingRatio(tt.configured)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("expected error, got ratio %v", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got != tt.want {
				t.Fatalf("ratio = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestResolveEndpoint(t *testing.T) {
	tests := []struct {
		name       string
		configured string
		signalEnv  string
		genericEnv string
		want       string
		wantErr    bool
	}{
		{name: "default", want: DefaultEndpoint},
		{name: "URL as used by the other services", configured: "http://collector:4317", want: "http://collector:4317"},
		{name: "host:port becomes plaintext URL", configured: "collector:4317", want: "http://collector:4317"},
		{name: "https kept for TLS", configured: "https://collector:4317", want: "https://collector:4317"},
		{name: "generic env", genericEnv: "http://env-collector:4317", want: "http://env-collector:4317"},
		{name: "generic env host:port", genericEnv: "env-collector:4317", want: "http://env-collector:4317"},
		{name: "signal env beats generic env", signalEnv: "signal:4317", genericEnv: "generic:4317", want: "http://signal:4317"},
		{name: "config beats env", configured: "cfg:4317", signalEnv: "signal:4317", genericEnv: "generic:4317", want: "http://cfg:4317"},
		{name: "unsupported scheme", configured: "ftp://collector:4317", wantErr: true},
		{name: "missing host", configured: "http://", wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv(EnvExporterTracesEndpoint, tt.signalEnv)
			t.Setenv(EnvExporterEndpoint, tt.genericEnv)
			got, err := resolveEndpoint(tt.configured, EnvExporterTracesEndpoint)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("expected error, got %q", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got != tt.want {
				t.Fatalf("endpoint = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestNewResource_FromEnv(t *testing.T) {
	t.Setenv(EnvServiceName, "")
	t.Setenv(EnvResourceAttributes, "service.name=from-attrs,deployment.environment.name=staging,team=gaze")

	res, err := newResource(context.Background(), Config{})
	if err != nil {
		t.Fatalf("newResource: %v", err)
	}
	if res.SchemaURL() != semconv.SchemaURL {
		t.Errorf("schema URL = %q, want %q", res.SchemaURL(), semconv.SchemaURL)
	}

	attrs := res.Set()
	want := map[attribute.Key]string{
		semconv.ServiceNameKey:               "from-attrs",
		semconv.DeploymentEnvironmentNameKey: "staging",
		"team":                               "gaze",
		semconv.TelemetrySDKLanguageKey:      "go",
	}
	for k, v := range want {
		got, ok := attrs.Value(k)
		if !ok || got.AsString() != v {
			t.Errorf("%s = %q (present=%v), want %q", k, got.AsString(), ok, v)
		}
	}
	for _, k := range []attribute.Key{semconv.HostNameKey, semconv.ProcessPIDKey, semconv.ServiceInstanceIDKey} {
		if _, ok := attrs.Value(k); !ok {
			t.Errorf("missing detected attribute %s", k)
		}
	}
	if _, ok := attrs.Value("deployment.environment"); ok {
		t.Error("legacy deployment.environment attribute must not be emitted")
	}
}

func TestNewResource_ConfigOverridesEnv(t *testing.T) {
	t.Setenv(EnvServiceName, "from-otel-service-name")
	t.Setenv(EnvResourceAttributes, "deployment.environment.name=staging,service.version=0.0.1")

	res, err := newResource(context.Background(), Config{
		ServiceName:    "post-query-service",
		ServiceVersion: "1.2.3",
		Environment:    "prod",
	})
	if err != nil {
		t.Fatalf("newResource: %v", err)
	}
	attrs := res.Set()
	for k, v := range map[attribute.Key]string{
		semconv.ServiceNameKey:               "post-query-service",
		semconv.ServiceVersionKey:            "1.2.3",
		semconv.DeploymentEnvironmentNameKey: "prod",
	} {
		if got, _ := attrs.Value(k); got.AsString() != v {
			t.Errorf("%s = %q, want %q", k, got.AsString(), v)
		}
	}
}

func TestNewResource_OTELServiceName(t *testing.T) {
	t.Setenv(EnvServiceName, "from-otel-service-name")
	t.Setenv(EnvResourceAttributes, "service.name=from-attrs")

	res, err := newResource(context.Background(), Config{})
	if err != nil {
		t.Fatalf("newResource: %v", err)
	}
	if got := serviceName(res); got != "from-otel-service-name" {
		t.Fatalf("service.name = %q, want OTEL_SERVICE_NAME to win", got)
	}
}

// sampledContext returns a context carrying a sampled span context and
// baggage, as a producer would have before publishing.
func sampledContext(t *testing.T) (context.Context, trace.SpanContext) {
	t.Helper()
	ctx := remoteParent(t, true)
	member, err := baggage.NewMember("tenant", "gaze")
	if err != nil {
		t.Fatal(err)
	}
	bag, err := baggage.New(member)
	if err != nil {
		t.Fatal(err)
	}
	return baggage.ContextWithBaggage(ctx, bag), trace.SpanContextFromContext(ctx)
}

func assertExtracted(t *testing.T, ctx context.Context, want trace.SpanContext) {
	t.Helper()
	got := trace.SpanContextFromContext(ctx)
	if !got.IsRemote() {
		t.Error("extracted span context is not marked remote")
	}
	if got.TraceID() != want.TraceID() || got.SpanID() != want.SpanID() || got.TraceFlags() != want.TraceFlags() {
		t.Errorf("extracted %s/%s/%s, want %s/%s/%s",
			got.TraceID(), got.SpanID(), got.TraceFlags(),
			want.TraceID(), want.SpanID(), want.TraceFlags())
	}
	if v := baggage.FromContext(ctx).Member("tenant").Value(); v != "gaze" {
		t.Errorf("baggage tenant = %q, want %q", v, "gaze")
	}
}

func TestAMQPHeaders_RoundTrip(t *testing.T) {
	SetupPropagator()
	ctx, want := sampledContext(t)

	headers := InjectAMQPHeaders(ctx, nil)
	if headers.Validate() != nil {
		t.Fatalf("injected headers are not a valid AMQP table: %v", headers.Validate())
	}
	if headers["traceparent"] == nil {
		t.Fatalf("traceparent not injected: %v", headers)
	}
	assertExtracted(t, ExtractAMQPHeaders(context.Background(), headers), want)
}

func TestAMQPHeaders_ByteValuesAndCaseInsensitiveKeys(t *testing.T) {
	SetupPropagator()
	ctx, want := sampledContext(t)

	injected := InjectAMQPHeaders(ctx, amqp.Table{"x-existing": int32(1)})
	if injected["x-existing"] != int32(1) {
		t.Fatal("existing header was lost")
	}

	// Simulate another client sending []byte values under different casing.
	headers := amqp.Table{}
	for k, v := range injected {
		if s, ok := v.(string); ok {
			headers[strings.ToUpper(k)] = []byte(s)
		}
	}
	assertExtracted(t, ExtractAMQPHeaders(context.Background(), headers), want)
}

func TestWatermillMetadata_RoundTrip(t *testing.T) {
	SetupPropagator()
	ctx, want := sampledContext(t)

	msg := message.NewMessage("id", []byte("payload"))
	msg.Metadata = InjectMetadata(ctx, msg.Metadata)
	if msg.Metadata.Get("traceparent") == "" {
		t.Fatalf("traceparent not injected: %v", msg.Metadata)
	}
	assertExtracted(t, ExtractMetadata(msg.Context(), msg.Metadata), want)

	if md := InjectMetadata(ctx, nil); md.Get("traceparent") == "" {
		t.Fatal("nil metadata was not allocated")
	}
}

func TestExtract_NoContextLeavesCtxUntouched(t *testing.T) {
	SetupPropagator()
	if sc := trace.SpanContextFromContext(ExtractAMQPHeaders(context.Background(), nil)); sc.IsValid() {
		t.Fatal("extracted a span context from nil headers")
	}
	if sc := trace.SpanContextFromContext(ExtractMetadata(context.Background(), message.Metadata{})); sc.IsValid() {
		t.Fatal("extracted a span context from empty metadata")
	}
}

// recordingHandler captures the attributes of every record it receives.
type recordingHandler struct {
	level   slog.Level
	records []map[string]string
}

func (h *recordingHandler) Enabled(_ context.Context, l slog.Level) bool { return l >= h.level }
func (h *recordingHandler) WithAttrs([]slog.Attr) slog.Handler           { return h }
func (h *recordingHandler) WithGroup(string) slog.Handler                { return h }
func (h *recordingHandler) Handle(_ context.Context, r slog.Record) error {
	attrs := map[string]string{"msg": r.Message}
	r.Attrs(func(a slog.Attr) bool {
		attrs[a.Key] = a.Value.String()
		return true
	})
	h.records = append(h.records, attrs)
	return nil
}

func TestFanoutHandler(t *testing.T) {
	var buf bytes.Buffer
	stdout := newStdoutHandler(&buf, slog.LevelInfo)
	other := &recordingHandler{level: slog.LevelWarn}
	logger := slog.New(NewFanoutHandler(stdout, other))

	ctx, sc := sampledContext(t)
	logger.InfoContext(ctx, "info only to stdout")
	logger.WarnContext(ctx, "warn to both", "k", "v")
	logger.DebugContext(ctx, "debug to nobody")

	lines := strings.Split(strings.TrimSpace(buf.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("stdout got %d lines, want 2:\n%s", len(lines), buf.String())
	}
	for _, line := range lines {
		var entry map[string]any
		if err := json.Unmarshal([]byte(line), &entry); err != nil {
			t.Fatalf("stdout line is not JSON: %q", line)
		}
		if entry["trace_id"] != sc.TraceID().String() || entry["span_id"] != sc.SpanID().String() {
			t.Errorf("stdout line missing trace correlation: %s", line)
		}
	}

	if len(other.records) != 1 {
		t.Fatalf("second handler got %d records, want 1", len(other.records))
	}
	rec := other.records[0]
	if rec["msg"] != "warn to both" || rec["k"] != "v" {
		t.Errorf("second handler got %v", rec)
	}
	if _, leaked := rec["trace_id"]; leaked {
		t.Error("trace_id added by the stdout branch leaked into the other handler")
	}
}

func TestInit_ExportersDisabled(t *testing.T) {
	prevLogger := slog.Default()
	prevProp := otel.GetTextMapPropagator()
	prevTP := otel.GetTracerProvider()
	prevMP := otel.GetMeterProvider()
	t.Cleanup(func() {
		slog.SetDefault(prevLogger)
		otel.SetTextMapPropagator(prevProp)
		otel.SetTracerProvider(prevTP)
		otel.SetMeterProvider(prevMP)
	})
	t.Setenv(EnvTracesExporter, "none")
	t.Setenv(EnvMetricsExporter, "none")
	t.Setenv(EnvLogsExporter, "none")
	t.Setenv(EnvTracesSamplerArg, "")

	var buf bytes.Buffer
	shutdown, err := Init(context.Background(), Config{ServiceName: "svc", LogOutput: &buf})
	if err != nil {
		t.Fatalf("Init: %v", err)
	}

	ctx, span := otel.Tracer("test").Start(context.Background(), "op")
	if !span.SpanContext().IsSampled() {
		t.Error("root span not sampled with default ratio 1")
	}
	slog.InfoContext(ctx, "hello")
	span.End()

	if !strings.Contains(buf.String(), span.SpanContext().TraceID().String()) {
		t.Errorf("log output missing trace_id:\n%s", buf.String())
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("second shutdown: %v", err)
	}
}

func TestInit_InvalidConfig(t *testing.T) {
	t.Setenv(EnvTracesSamplerArg, "")
	t.Setenv(EnvTracesExporter, "")
	if _, err := Init(context.Background(), Config{SamplingRatio: 2}); err == nil {
		t.Error("expected error for sampling ratio 2")
	}
	if _, err := Init(context.Background(), Config{Endpoint: "ftp://collector:4317"}); err == nil {
		t.Error("expected error for ftp endpoint")
	}
}
