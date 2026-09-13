package server

import "os"

type ServerConfig struct {
    Port string
}

func LoadServer() ServerConfig {
    port := os.Getenv("HTTP_PORT")
    if port == "" {
        port = "8080"
    }
    return ServerConfig{Port: port}
}