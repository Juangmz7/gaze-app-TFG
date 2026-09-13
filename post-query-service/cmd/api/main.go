package main

import (
	"context"
	"log/slog"
	"os"

	"github.com/Juangmz7/TFG/common-packages/go-utils/server"
)


func main() {
	err := run()
	if err != nil {
		slog.Error("application failed", "error", err)
		os.Exit(1)	
	}
}

func run() error {
	serverCfg := server.LoadServer()
	ctx := context.Background()

	err := server.StartServer(ctx, serverCfg.Port, nil)
	if err != nil {
		return err
	}

	return nil
}