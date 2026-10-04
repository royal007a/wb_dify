-- Legacy vectors and old corpus digests remain unchanged. New profiles are fixed per KB.
ALTER TABLE knowledge_bases ADD COLUMN embedding_profile TEXT;
ALTER TABLE document_chunks ADD COLUMN semantic_profile TEXT;
ALTER TABLE document_chunks ADD COLUMN semantic_vector TEXT;
ALTER TABLE document_chunks ADD CONSTRAINT ck_semantic_pair CHECK
  ((semantic_profile IS NULL AND semantic_vector IS NULL) OR
   (semantic_profile IS NOT NULL AND semantic_vector IS NOT NULL));
