package server

import (
	"context"
	"net/http"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
)

func StartServer(ctx context.Context, cfg *auth.AuthConfig, router *chi.Mux) (http.Handler, error) {
	keyCache, err := auth.NewCacheKey(ctx, cfg.JWKSUrl)
	if err != nil {
		return nil, err
	}

	router.Use(middleware.Logger)
	router.Use(middleware.Recoverer)

	// Public routes 
	router.Get("/health", handler.HealthHandler)

	// Protected routes
	router.Route("/api/v1", func(r chi.Router) {
		r.Group(func(r chi.Router) {
			// Ponemos el middleware aquí, protegiendo solo este grupo
			r.Use(auth.Authenticate(keyCache, cfg.Issuer, cfg.Audience))

			r.Get("/protected", handler.Protected)

			// Dominio de Productos
			r.Route("/products", func(r chi.Router) {
				r.Get("/{id}", orderHandler.GetOrderById)    // GET /api/v1/products/{id}
				r.Post("/",	orderHandler.CreateOrder)       // POST /api/v1/products/
				r.Put("/{id}", orderHandler.UpdateOrder)    // PUT /api/v1/products/
				r.Delete("/{id}", orderHandler.DeleteOrder) // DELETE /api/v1/products/
			})
		})
	})

	return router, nil
}
