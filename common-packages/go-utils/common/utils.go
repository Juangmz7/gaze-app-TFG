package common

import (
	"encoding/json"
	"fmt"
)

// ParseAnyToBytes converts an unknown 'any' type into a slice of bytes.
func ParseAnyToBytes(value any) ([]byte, error) {
	if value == nil {
		return nil, fmt.Errorf("cannot parse nil value to bytes")
	}

	switch v := value.(type) {
	case []byte:
		// It's already a byte slice, return as is
		return v, nil

	case string:
		// It's a string, safely cast it to bytes
		return []byte(v), nil

	default:
		// It's a struct, map, int, or boolean.
		// Fallback to JSON serialization to get the byte representation.
		data, err := json.Marshal(v)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal value to json bytes: %w", err)
		}
		return data, nil
	}
}