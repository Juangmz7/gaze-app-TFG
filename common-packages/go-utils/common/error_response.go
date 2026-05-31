package common

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/google/uuid"
)

type ApiErrorDetail struct {
	Field string `json:"field"`
	Issue string `json:"issue"`
}

type ApiErrorResponse struct {
	Status    int              `json:"status"`
	Code      string           `json:"code"`
	Message   string           `json:"message"`
	Timestamp time.Time        `json:"timestamp"`
	Path      string           `json:"path"`
	TraceID   string           `json:"trace_id"`
	Details   []ApiErrorDetail `json:"details,omitempty"`
}

func RespondWithError(w http.ResponseWriter, r *http.Request, status int, code, message string, details []ApiErrorDetail) {
	traceID := r.Header.Get("X-Trace-Id")
	if traceID == "" {
		traceID = uuid.New().String()
	}

	w.Header().Set("Content-Type", "Application/json")
	w.WriteHeader(status)

	response := ApiErrorResponse{
		Status:    status,
		Code:      code,
		Message:   message,
		Timestamp: time.Now().UTC(),
		Path:      r.URL.Path,
		TraceID:   traceID,
		Details:   details,
	}

	json.NewEncoder(w).Encode(response)
}