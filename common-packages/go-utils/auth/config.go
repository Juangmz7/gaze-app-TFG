package auth

import "os"

type AuthConfig struct {
    JWKSUrl  string
    Issuer   string
    Audience string
}

func LoadAuth() AuthConfig {
    return AuthConfig{
        JWKSUrl:  os.Getenv("JWKS_URL"),
        Issuer:   os.Getenv("JWT_ISSUER"),
        Audience: os.Getenv("JWT_AUDIENCE"),
    }
}