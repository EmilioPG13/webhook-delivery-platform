package io.github.emiliopg13.webhooks.endpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Every query takes the tenant id: a row of another tenant is indistinguishable from a missing one. */
interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

	@Query("""
			select e from Endpoint e
			where e.id = :id and e.tenantId = :tenantId
			  and (e.disabledReason is null or e.disabledReason <> 'deleted')
			""")
	Optional<Endpoint> findVisible(UUID id, UUID tenantId);

	@Query("""
			select e from Endpoint e
			where e.tenantId = :tenantId
			  and (e.disabledReason is null or e.disabledReason <> 'deleted')
			order by e.id desc
			""")
	List<Endpoint> findFirstPage(UUID tenantId, Limit limit);

	@Query("""
			select e from Endpoint e
			where e.tenantId = :tenantId and e.id < :cursor
			  and (e.disabledReason is null or e.disabledReason <> 'deleted')
			order by e.id desc
			""")
	List<Endpoint> findPageAfter(UUID tenantId, UUID cursor, Limit limit);

}
