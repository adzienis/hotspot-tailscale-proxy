package cmd

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"go.uber.org/zap"
)

func TestRewriteTailscaleURLForBase(t *testing.T) {
	tests := []struct {
		name        string
		input       string
		advertise   string
		want        string
	}{
		{
			name:      "https login rewritten to local http ip and port",
			input:     "https://login.tailscale.com/a/device",
			advertise: "http://192.168.43.1:8080",
			want:      "http://192.168.43.1:8080/a/device",
		},
		{
			name:      "protocol relative rewritten with port",
			input:     "//controlplane.tailscale.com/key",
			advertise: "http://192.168.43.1:8080",
			want:      "//192.168.43.1:8080/key",
		},
		{
			name:      "quoted json url rewritten",
			input:     `"https://controlplane.tailscale.com/register"`,
			advertise: "http://192.168.43.1:8080",
			want:      `"http://192.168.43.1:8080/register"`,
		},
		{
			name:      "existing hotspot url stays unchanged",
			input:     "http://192.168.43.1:8080/register",
			advertise: "http://192.168.43.1:8080",
			want:      "http://192.168.43.1:8080/register",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := rewriteTailscaleURLForBase(tt.input, tt.advertise); got != tt.want {
				t.Fatalf("rewriteTailscaleURLForBase(%q, %q) = %q, want %q", tt.input, tt.advertise, got, tt.want)
			}
		})
	}
}

func TestAdvertisedBaseURLUsesOriginalRequestHeaders(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet, "https://controlplane.tailscale.com/key", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("X-Proxyt-Original-Proto", "http")
	req.Header.Set("X-Proxyt-Original-Host", "192.168.43.1:8080")

	previousBaseURL := baseURL
	baseURL = ""
	t.Cleanup(func() {
		baseURL = previousBaseURL
	})

	if got, want := advertisedBaseURL(req), "http://192.168.43.1:8080"; got != want {
		t.Fatalf("advertisedBaseURL() = %q, want %q", got, want)
	}
}

func TestMainHandlerServesHealthEndpoint(t *testing.T) {
	logger = zap.NewNop()
	handler := newMainHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatalf("health request should not reach proxy handler")
	}))

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "http://example.com/health", nil)
	handler.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("health status = %d, want %d", recorder.Code, http.StatusOK)
	}
	if body := recorder.Body.String(); body != "OK - Tailscale Proxy is running" {
		t.Fatalf("health body = %q, want %q", body, "OK - Tailscale Proxy is running")
	}
}

func TestMainHandlerProxiesRequestsForHTTPOnlyMode(t *testing.T) {
	logger = zap.NewNop()
	proxyHit := false
	handler := newMainHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxyHit = true
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte("proxied"))
	}))

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "http://example.com/control/status", nil)
	handler.ServeHTTP(recorder, request)

	if !proxyHit {
		t.Fatal("expected request to reach proxy handler")
	}
	if recorder.Code != http.StatusAccepted {
		t.Fatalf("proxy status = %d, want %d", recorder.Code, http.StatusAccepted)
	}
}

func TestRewriteTailscaleURLsInBodyUsesHotspotLocalBase(t *testing.T) {
	body := `{"url":"https://login.tailscale.com/a/device","next":"https://controlplane.tailscale.com/register"}`
	got := rewriteTailscaleURLsInBody(body, "http://192.168.43.1:8080")

	want := `{"url":"http://192.168.43.1:8080/a/device","next":"http://192.168.43.1:8080/register"}`
	if got != want {
		t.Fatalf("rewriteTailscaleURLsInBody() = %q, want %q", got, want)
	}
}
