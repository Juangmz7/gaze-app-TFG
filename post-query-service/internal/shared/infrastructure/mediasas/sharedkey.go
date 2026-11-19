package mediasas

import (
	"context"
	"fmt"

	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/sas"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/service"
)

// SharedKeySource signs with the storage account key. It is meant for Azurite only:
// config refuses an account key unless the account URL is plain HTTP, so production
// signs with a user delegation key and holds no account key.
type SharedKeySource struct {
	credential sharedKeyCredential
}

// NewSharedKeySource creates a SharedKeySource for accountName and its base64 key.
func NewSharedKeySource(accountName, accountKey string) (*SharedKeySource, error) {
	credential, err := service.NewSharedKeyCredential(accountName, accountKey)
	if err != nil {
		return nil, fmt.Errorf("media sas: account key: %w", err)
	}

	return &SharedKeySource{credential: sharedKeyCredential{credential: credential}}, nil
}

// Credential returns the account key credential, whatever the validity.
func (s *SharedKeySource) Credential(context.Context, Validity) (Credential, error) {
	return s.credential, nil
}

type sharedKeyCredential struct {
	credential *service.SharedKeyCredential
}

func (c sharedKeyCredential) Sign(values sas.BlobSignatureValues) (sas.QueryParameters, error) {
	return values.SignWithSharedKey(c.credential)
}
