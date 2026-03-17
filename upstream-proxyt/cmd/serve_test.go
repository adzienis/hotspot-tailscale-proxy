package cmd

import (
	"net/http"
	"testing"
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
