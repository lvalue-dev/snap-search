package com.snap_search.lvalue.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 외부 공급자 ID ↔ 내부 ID 매핑. 내부 ID 체계는 기존 데이터(api-football)를 그대로 유지한다.
 */
@Entity
@Table(name = "external_id_map", uniqueConstraints = @UniqueConstraint(
	name = "uk_external_id_map", columnNames = {"provider", "entity_type", "external_id"}))
public class ExternalIdMap {

	public enum EntityType { TEAM, FIXTURE, LEAGUE, PLAYER }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String provider;

	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	@jakarta.persistence.Column(name = "entity_type")
	private EntityType entityType;

	@jakarta.persistence.Column(name = "external_id")
	private String externalId;

	@jakarta.persistence.Column(name = "internal_id")
	private Long internalId;

	/** 이름 매칭으로 자동 생성된 경우 true. 관리자가 검수 후 바꿀 수 있도록 표시. */
	private boolean auto;

	public ExternalIdMap() {
	}

	public ExternalIdMap(String provider, EntityType entityType, String externalId, Long internalId, boolean auto) {
		this.provider = provider;
		this.entityType = entityType;
		this.externalId = externalId;
		this.internalId = internalId;
		this.auto = auto;
	}

	public Long getId() {
		return id;
	}

	public String getProvider() {
		return provider;
	}

	public EntityType getEntityType() {
		return entityType;
	}

	public String getExternalId() {
		return externalId;
	}

	public Long getInternalId() {
		return internalId;
	}

	public void setInternalId(Long internalId) {
		this.internalId = internalId;
	}

	public boolean isAuto() {
		return auto;
	}

	public void setAuto(boolean auto) {
		this.auto = auto;
	}
}
