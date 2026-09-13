package com.hify.infra;

import com.hify.domain.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelProviderRepository extends JpaRepository<ModelProvider, String> {}

