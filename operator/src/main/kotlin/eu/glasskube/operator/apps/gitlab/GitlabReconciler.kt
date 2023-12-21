package eu.glasskube.operator.apps.gitlab

import eu.glasskube.kubernetes.client.patchOrUpdateStatus
import eu.glasskube.operator.Labels
import eu.glasskube.operator.api.reconciler.getSecondaryResource
import eu.glasskube.operator.api.reconciler.informerEventSource
import eu.glasskube.operator.apps.gitlab.dependent.GitlabCloudStorageBackupCronJob
import eu.glasskube.operator.apps.gitlab.dependent.GitlabConfigMap
import eu.glasskube.operator.apps.gitlab.dependent.GitlabDeployment
import eu.glasskube.operator.apps.gitlab.dependent.GitlabIngress
import eu.glasskube.operator.apps.gitlab.dependent.GitlabMinioBucket
import eu.glasskube.operator.apps.gitlab.dependent.GitlabPostgresBackup
import eu.glasskube.operator.apps.gitlab.dependent.GitlabPostgresCluster
import eu.glasskube.operator.apps.gitlab.dependent.GitlabRegistryIngress
import eu.glasskube.operator.apps.gitlab.dependent.GitlabRunners
import eu.glasskube.operator.apps.gitlab.dependent.GitlabSSHService
import eu.glasskube.operator.apps.gitlab.dependent.GitlabService
import eu.glasskube.operator.apps.gitlab.dependent.GitlabServiceMonitor
import eu.glasskube.operator.apps.gitlab.dependent.GitlabVeleroBackupStorageLocation
import eu.glasskube.operator.apps.gitlab.dependent.GitlabVeleroSchedule
import eu.glasskube.operator.apps.gitlab.dependent.GitlabVeleroSecret
import eu.glasskube.operator.apps.gitlab.dependent.GitlabVolume
import eu.glasskube.operator.apps.gitlab.runner.GitlabRunner
import eu.glasskube.operator.generic.BaseReconciler
import eu.glasskube.operator.infra.postgres.PostgresCluster
import eu.glasskube.operator.webhook.WebhookService
import eu.glasskube.utils.logger
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent

@ControllerConfiguration(
    dependents = [
        Dependent(
            type = GitlabMinioBucket::class,
            name = "GitlabMinioBucket",
            reconcilePrecondition = GitlabMinioBucket.ReconcilePrecondition::class
        ),
        Dependent(type = GitlabConfigMap::class, name = "GitlabConfigMap"),
        Dependent(type = GitlabVolume::class, name = "GitlabVolume"),
        Dependent(
            type = GitlabPostgresCluster::class,
            name = "GitlabPostgresCluster",
            readyPostcondition = GitlabPostgresCluster.ReadyPostCondition::class
        ),
        Dependent(
            type = GitlabPostgresBackup::class,
            name = "GitlabPostgresBackup",
            dependsOn = ["GitlabPostgresCluster"]
        ),
        Dependent(
            type = GitlabDeployment::class,
            name = "GitlabDeployment",
            dependsOn = ["GitlabVolume", "GitlabConfigMap", "GitlabPostgresCluster"]
        ),
        Dependent(
            type = GitlabService::class,
            name = "GitlabService",
            useEventSourceWithName = GitlabReconciler.SERVICE_EVENT_SOURCE
        ),
        Dependent(
            type = GitlabSSHService::class,
            name = "GitlabSSHService",
            useEventSourceWithName = GitlabReconciler.SERVICE_EVENT_SOURCE,
            reconcilePrecondition = GitlabSSHService.ReconcileCondition::class
        ),
        Dependent(
            type = GitlabServiceMonitor::class,
            name = "GitlabServiceMonitor",
            dependsOn = ["GitlabService"]
        ),
        Dependent(
            type = GitlabIngress::class,
            name = "GitlabIngress",
            useEventSourceWithName = GitlabReconciler.INGRESS_EVENT_SOURCE,
            dependsOn = ["GitlabService"]
        ),
        Dependent(
            type = GitlabRegistryIngress::class,
            name = "GitlabRegistryIngress",
            reconcilePrecondition = GitlabRegistryIngress.ReconcilePrecondition::class,
            useEventSourceWithName = GitlabReconciler.INGRESS_EVENT_SOURCE,
            dependsOn = ["GitlabService"]
        ),
        Dependent(
            type = GitlabRunners::class,
            dependsOn = ["GitlabDeployment"]
        ),
        Dependent(
            type = GitlabCloudStorageBackupCronJob::class,
            name = "GitlabCloudStorageBackupCronJob",
            reconcilePrecondition = GitlabCloudStorageBackupCronJob.ReconcilePrecondition::class
        ),
        Dependent(
            type = GitlabVeleroSecret::class,
            name = "GitlabVeleroSecret",
            reconcilePrecondition = GitlabVeleroSecret.ReconcilePrecondition::class
        ),
        Dependent(
            type = GitlabVeleroBackupStorageLocation::class,
            name = "GitlabVeleroBackupStorageLocation",
            dependsOn = ["GitlabVeleroSecret"]
        ),
        Dependent(
            type = GitlabVeleroSchedule::class,
            name = "GitlabVeleroSchedule",
            dependsOn = ["GitlabVeleroBackupStorageLocation"]
        )
    ]
)
class GitlabReconciler(webhookService: WebhookService) :
    BaseReconciler<Gitlab>(webhookService), EventSourceInitializer<Gitlab> {

    override fun processReconciliation(resource: Gitlab, context: Context<Gitlab>) = with(context) {
        resource.patchOrUpdateStatus(
            GitlabStatus(
                getSecondaryResource<Deployment>().map { it.status?.readyReplicas ?: 0 }.orElse(0),
                getSecondaryResource<PostgresCluster>().map { it.status?.readyInstances?.let { it > 0 } }.orElse(false),
                getSecondaryResources(GitlabRunner::class.java).associate { it.metadata.name to it.status }
            )
        )
    }

    override fun prepareEventSources(context: EventSourceContext<Gitlab>) = with(context) {
        mutableMapOf(
            SERVICE_EVENT_SOURCE to informerEventSource<Service>(SELECTOR),
            INGRESS_EVENT_SOURCE to informerEventSource<Ingress>(SELECTOR)
        )
    }

    companion object {
        const val SELECTOR =
            "${Labels.MANAGED_BY_GLASSKUBE},${Labels.PART_OF}=${Gitlab.APP_NAME},${Labels.NAME}=${Gitlab.APP_NAME}"

        internal const val SERVICE_EVENT_SOURCE = "GitlabServiceEventSource"
        internal const val INGRESS_EVENT_SOURCE = "GitlabIngressEventSource"

        @JvmStatic
        private val log = logger()
    }
}
