package com.snap_search.lvalue.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.snap_search.lvalue.model.ExternalIdMap;

public interface ExternalIdMapRepository extends JpaRepository<ExternalIdMap, Long> {
	Optional<ExternalIdMap> findByProviderAndEntityTypeAndExternalId(String provider,
		ExternalIdMap.EntityType entityType, String externalId);

	List<ExternalIdMap> findByProviderAndEntityType(String provider, ExternalIdMap.EntityType entityType);
}
