package io.github.emiliopg13.webhooks.tenant;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TenantRepository extends JpaRepository<Tenant, UUID> {

}
