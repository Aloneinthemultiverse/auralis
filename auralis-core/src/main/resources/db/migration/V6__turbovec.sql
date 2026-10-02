-- TurboVec (TurboQuant-compressed index, external service) becomes the ANN stage.
-- Each chunk gets a numeric id for the index; exact embeddings stay here for exact
-- re-scoring of the candidates, so the large HNSW graph is no longer needed.
ALTER TABLE chunk ADD COLUMN vec_id BIGSERIAL;
CREATE UNIQUE INDEX chunk_vec_id_idx ON chunk (vec_id);
DROP INDEX IF EXISTS chunk_embedding_hnsw;
