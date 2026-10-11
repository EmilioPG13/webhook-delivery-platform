{{/*
One Deployment, Service and PodDisruptionBudget for a role. Called with a dict: root (the chart context),
role ("api" or "worker") and cfg (that role's block of values). It also serves as the selector helper's argument,
which reads root and role.
*/}}
{{- define "webhooks.workload" -}}
{{- $root := .root -}}
{{- $role := .role -}}
{{- $cfg := .cfg -}}
{{- if $root.Values.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "webhooks.fullname" $root }}-{{ $role }}
  labels:
    {{- include "webhooks.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ $role }}
---
{{- end }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "webhooks.fullname" $root }}-{{ $role }}
  labels:
    {{- include "webhooks.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ $role }}
spec:
  replicas: {{ $cfg.replicas }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      # Never drop below the desired count while updating: a new pod must be ready before an old one goes.
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      {{- include "webhooks.selector" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "webhooks.labels" $root | nindent 8 }}
        app.kubernetes.io/component: {{ $role }}
      annotations:
        # A changed ConfigMap or Secret restarts the pods, because environment variables are read only at start.
        checksum/config: {{ include (print $root.Template.BasePath "/configmap.yaml") $root | sha256sum }}
        checksum/secret: {{ include (print $root.Template.BasePath "/secret.yaml") $root | sha256sum }}
    spec:
      {{- if $root.Values.serviceAccount.create }}
      serviceAccountName: {{ include "webhooks.fullname" $root }}-{{ $role }}
      {{- end }}
      {{- with $root.Values.image.pullSecrets }}
      imagePullSecrets:
        {{- range . }}
        - name: {{ . }}
        {{- end }}
      {{- end }}
      terminationGracePeriodSeconds: {{ if eq $role "worker" }}{{ $cfg.terminationGracePeriodSeconds }}{{ else }}30{{ end }}
      securityContext:
        runAsNonRoot: true
        # The image's own user is named, not numbered, and Kubernetes can only verify a number.
        runAsUser: 10001
        runAsGroup: 10001
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: app
          image: "{{ $root.Values.image.repository }}:{{ $root.Values.image.tag }}"
          imagePullPolicy: {{ $root.Values.image.pullPolicy }}
          envFrom:
            - configMapRef:
                name: {{ include "webhooks.fullname" $root }}
            - secretRef:
                name: {{ include "webhooks.secretName" $root }}
          {{- if eq $role "worker" }}
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: worker
          {{- end }}
          ports:
            - name: http
              containerPort: 8080
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            {{- toYaml $root.Values.probes.startup | nindent 12 }}
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            {{- toYaml $root.Values.probes.liveness | nindent 12 }}
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: http }
            {{- toYaml $root.Values.probes.readiness | nindent 12 }}
          {{- if eq $role "api" }}
          lifecycle:
            preStop:
              # The pod leaves the Service's endpoints at the same moment SIGTERM arrives. A short wait keeps the
              # last requests routed to it from meeting a closed port.
              exec:
                command: ["sleep", "5"]
          {{- end }}
          resources:
            {{- toYaml $cfg.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          volumeMounts:
            # Tomcat and the JVM write temporary files; the rest of the filesystem stays read-only.
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "webhooks.fullname" $root }}-{{ $role }}
  labels:
    {{- include "webhooks.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ $role }}
spec:
  selector:
    {{- include "webhooks.selector" . | nindent 4 }}
  ports:
    - name: http
      port: 8080
      targetPort: http
{{- if $root.Values.podDisruptionBudget.enabled }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "webhooks.fullname" $root }}-{{ $role }}
  labels:
    {{- include "webhooks.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ $role }}
spec:
  minAvailable: {{ $root.Values.podDisruptionBudget.minAvailable }}
  selector:
    matchLabels:
      {{- include "webhooks.selector" . | nindent 6 }}
{{- end }}
{{- end -}}
