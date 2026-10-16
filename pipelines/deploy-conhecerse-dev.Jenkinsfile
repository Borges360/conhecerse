def REGISTRY = 'registry.platform.svc.cluster.local:5000'
def REPO_URL = 'git@github.com:Borges360/conhecerse.git'
def TEST_POD = '''
apiVersion: v1
kind: Pod
spec:
  securityContext: {fsGroup: 1000}
  containers:
    - name: node
      image: mcr.microsoft.com/playwright:v1.64.0-noble@sha256:06a9939e57531807f8d5fd76ce44b53165ffb7d7501d87ab10e285c20b1e971f
      command: [sleep]
      args: [infinity]
      resources:
        requests: {cpu: 200m, memory: 256Mi}
        limits: {memory: 1024Mi}
    - name: contracts
      image: python:3.13-slim-bookworm@sha256:a1165e272e578941b84abc79e4ab38a0305cd12803a5c4247979ac7655f4d641
      command: [sleep]
      args: [infinity]
      resources:
        requests: {cpu: 50m, memory: 128Mi}
        limits: {memory: 512Mi}
    - name: maven
      image: maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320
      command: [sleep]
      volumeMounts:
        - {name: maven-cache, mountPath: /maven-cache}
      args: [infinity]
      resources:
        requests: {cpu: 200m, memory: 256Mi}
        limits: {memory: 1024Mi}
    - name: postgres
      image: postgres:17.11-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24
      env:
        - {name: POSTGRES_USER, value: postgres}
        - {name: POSTGRES_PASSWORD, value: synthetic_ci_only}
      resources:
        requests: {cpu: 50m, memory: 64Mi}
        limits: {memory: 256Mi}
    - name: trivy
      image: aquasec/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa
      command: [sleep]
      volumeMounts:
        - {name: maven-cache, mountPath: /maven-cache}
      args: [infinity]
      resources:
        requests: {cpu: 100m, memory: 64Mi}
        limits: {memory: 512Mi}
    - name: jnlp
      resources:
        requests: {cpu: 100m, memory: 256Mi}
        limits: {memory: 512Mi}
  volumes:
    - name: maven-cache
      emptyDir: {}
'''
def BUILD_POD = '''
apiVersion: v1
kind: Pod
spec:
  securityContext: {fsGroup: 1000}
  containers:
    - name: buildkit
      image: moby/buildkit:v0.23.2-rootless@sha256:cab936745de5d673465948f1e93ff4d6e372bbe33f218afd3314eba45a6f85a9
      command: [sleep]
      args: [infinity]
      env: [{name: BUILDKITD_FLAGS, value: --oci-worker-no-process-sandbox}]
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        seccompProfile: {type: Unconfined}
        appArmorProfile: {type: Unconfined}
      resources:
        requests: {cpu: 500m, memory: 1536Mi}
        limits: {memory: 2560Mi}
      volumeMounts:
        - {name: dockerfile, mountPath: /config}
        - {name: buildkit-state, mountPath: /home/user/.local/share/buildkit}
    - name: jnlp
      resources:
        requests: {cpu: 100m, memory: 256Mi}
        limits: {memory: 512Mi}
  volumes:
    - name: dockerfile
      configMap: {name: conhecerse-dockerfile}
    - name: buildkit-state
      emptyDir: {}
'''
def DEPLOY_POD = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: lab-deployer
  containers:
    - name: kubectl
      image: alpine/k8s:1.33.4@sha256:b0523f0a244ddc4c8e055aa335c040d3d78b3ead5528f4544395f7f9f69c7b68
      command: [sleep]
      args: [infinity]
      resources:
        requests: {cpu: 50m, memory: 64Mi}
        limits: {memory: 256Mi}
    - name: jnlp
      resources:
        limits: {memory: 512Mi}
'''
def SCAN_POD = '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: trivy
      image: aquasec/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa
      command: [sleep]
      args: [infinity]
      resources:
        requests: {cpu: 100m, memory: 256Mi}
        limits: {memory: 1024Mi}
    - name: jnlp
      resources:
        limits: {memory: 512Mi}
'''

def DAST_POD = '''
apiVersion: v1
kind: Pod
spec:
  securityContext: {fsGroup: 1000}
  hostAliases:
    - ip: 10.43.41.43
      hostnames: [conhecerse.lab, conhecerse-auth.lab]
  containers:
    - name: zap
      image: ghcr.io/zaproxy/zaproxy:stable@sha256:5ccfff9a411743ab2615136a2edd7416bec9ce41572fa94e935df38c14adb2b6
      command: [sleep]
      args: [infinity]
      resources:
        requests: {cpu: 250m, memory: 512Mi}
        limits: {memory: 1536Mi}
    - name: jnlp
      resources:
        limits: {memory: 512Mi}
'''
pipeline {
  agent none
  options {timestamps(); ansiColor('xterm'); disableConcurrentBuilds(); buildDiscarder(logRotator(numToKeepStr:'20')); timeout(time:60,unit:'MINUTES')}
  parameters {
    string(name:'BRANCH',defaultValue:'main',description:'Branch do repositório Conhecer-se')
    booleanParam(name:'SKIP_TEST',defaultValue:false,description:'Exceção explícita para pular testes. Não utilizar para liberação de produção.')
    booleanParam(name:'DRY_RUN',defaultValue:false,description:'Executa testes e builds sem push, migrações ou deploy')
  }
  stages {
    stage('Checkout, contratos e qualidade') {
      steps {script {podTemplate(yaml:TEST_POD) {node(POD_LABEL) {dir('app') {
        checkout([$class:'GitSCM',branches:[[name:"*/${params.BRANCH}"]],userRemoteConfigs:[[url:REPO_URL,credentialsId:'github-ssh']],extensions:[[$class:'CloneOption',shallow:true,depth:1,noTags:true]]])
        env.SOURCE_SHA=sh(returnStdout:true,script:'git rev-parse HEAD').trim()
        env.IMAGE_TAG="${env.BUILD_NUMBER}-${env.SOURCE_SHA.take(7)}"
        currentBuild.description="${params.BRANCH} ${env.IMAGE_TAG}"
        if(!params.SKIP_TEST) {
          container('node') {sh '''
            set -eu
            corepack enable
            corepack prepare pnpm@10.28.2 --activate
            pnpm install --frozen-lockfile
          '''}
          container('contracts') {sh '''
            set -eu
            python -m venv /tmp/contracts
            /tmp/contracts/bin/pip install --disable-pip-version-check PyYAML==6.0.3
            /tmp/contracts/bin/python scripts/generate_contracts.py --check
            /tmp/contracts/bin/python scripts/generate_events.py --check
          '''}
          container('node') {sh '''
            set -eu
            pnpm typecheck
            pnpm test
            NODE_OPTIONS=--max-old-space-size=768 pnpm build
            PLAYWRIGHT_CHANNEL=chromium pnpm test:e2e
          '''}
          container('maven') {sh '''
            set -eu
            export MAVEN_OPTS="-Xmx128m -XX:MaxMetaspaceSize=128m"
            export TEST_PG_ADMIN_URL=jdbc:postgresql://127.0.0.1:5432/postgres
            export TEST_PG_ADMIN_USER=postgres
            export TEST_PG_ADMIN_PASSWORD=synthetic_ci_only
            cd backend
            chmod +x mvnw
            ./mvnw -B -q -Dmaven.repo.local=/maven-cache/.m2/repository -Prelease-gates verify
          '''}
          container('contracts') {sh '/tmp/contracts/bin/python scripts/check-critical-quality.py'}
          container('trivy') {sh '''
            set -eu
            trivy fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --skip-dirs .git,node_modules,apps/web/.next,backend .
            HOME=/maven-cache trivy sbom --offline-scan --scanners vuln --severity HIGH,CRITICAL --exit-code 1 backend/target/bom.json
            HOME=/maven-cache trivy fs --offline-scan --scanners secret --severity HIGH,CRITICAL --exit-code 1 --skip-dirs .git,node_modules,apps/web/.next,backend/target .
            trivy fs --offline-scan --format cyclonedx --output sbom-web.cdx.json --skip-dirs node_modules,.next apps/web
            cp backend/target/bom.json sbom-java.cdx.json
          '''}
        }
        stash(name:'source',includes:'**',excludes:'**/node_modules/**,**/.next/**,**/target/**,.local/**,**/test-results/**,**/playwright-report/**')
        if(!params.SKIP_TEST){junit testResults:'backend/target/surefire-reports/*.xml,apps/web/test-results/*.xml';archiveArtifacts artifacts:'sbom-java.cdx.json,sbom-web.cdx.json,apps/web/test-results/*.png,backend/target/site/jacoco/**,backend/target/pit-reports/**',allowEmptyArchive:false}
      }}}}}
    }
    stage('BuildKit rootless') {
      steps {script {podTemplate(yaml:BUILD_POD) {node(POD_LABEL) {dir('app') {
        unstash 'source'
        container('buildkit') {withEnv(["REGISTRY=${REGISTRY}","PUSH=${!params.DRY_RUN}"]) {sh '''
          set -eu
          mkdir -p /tmp/df
          cp -L /config/Dockerfile /config/Dockerfile.dockerignore /tmp/df/
          for target in web api iam av; do
            case "$target" in web) image=conhecerse ;; *) image=conhecerse-$target ;; esac
            buildctl-daemonless.sh build --frontend dockerfile.v0 --local context=. --local dockerfile=/tmp/df --opt filename=Dockerfile --opt target=$target --output "type=image,name=$REGISTRY/$image:$IMAGE_TAG,push=$PUSH,registry.insecure=true"
          done
        '''}}
      }}}}}
    }
    stage('SCA e SBOM das imagens') {
      when {expression {return !params.DRY_RUN}}
      steps {script {podTemplate(yaml:SCAN_POD){node(POD_LABEL){container('trivy'){withEnv(["REGISTRY=${REGISTRY}"]){sh '''
        set -eu
        for component in web api iam av; do
          case "$component" in web) image=conhecerse ;; *) image=conhecerse-$component ;; esac
          trivy image --insecure --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 --format json --output "scan-$component.json" "$REGISTRY/$image:$IMAGE_TAG"
          trivy image --insecure --format cyclonedx --output "sbom-$component.cdx.json" "$REGISTRY/$image:$IMAGE_TAG"
        done
      '''}};archiveArtifacts artifacts:'scan-*.json,sbom-*.cdx.json',allowEmptyArchive:false}}}}
    }
    stage('Capacidade antes de migrar') {
      when {expression {return !params.DRY_RUN}}
      steps {script {podTemplate(yaml:DEPLOY_POD){node(POD_LABEL){container('kubectl'){sh '''
        set -eu
        # Existing memory ceilings are retained. Reject deploy if measured headroom cannot cover the application.
        metrics=$(curl --fail --silent --show-error --get --data-urlencode 'query=sum(kube_node_status_allocatable{resource="memory",unit="byte"})-sum(container_memory_working_set_bytes{container!="",container!="POD",namespace!=""})-2147483648' http://kube-prometheus-stack-prometheus.observability.svc.cluster.local:9090/api/v1/query)
        available=$(printf '%s' "$metrics" | jq -er '.data.result[0].value[1] | tonumber | floor')
        [ "$available" -ge 5100273664 ] || { echo 'Capacidade insuficiente: requer 3840 MiB da aplicação + 1024 MiB de margem além da reserva do host. Não aumentar a VM automaticamente.'; exit 1; }
      '''}}}}}
    }
    stage('Migrações Flyway') {
      when {expression {return !params.DRY_RUN}}
      steps {script {
        def migrationPod="""
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: migrate
      image: ${REGISTRY}/conhecerse-api:${env.IMAGE_TAG}
      securityContext: {runAsNonRoot: true, runAsUser: 1000, runAsGroup: 1000}
      command: [sleep]
      args: [infinity]
      envFrom: [{secretRef: {name: conhecerse-dev-db}}]
      resources:
        limits: {memory: 512Mi}
    - name: jnlp
      resources:
        limits: {memory: 512Mi}
"""
        podTemplate(yaml:migrationPod){node(POD_LABEL){container('migrate'){sh 'java -jar /app/app.jar migrate'}}}
      }}
    }
    stage('Deploy no namespace dev') {
      when {expression {return !params.DRY_RUN}}
      steps {script {podTemplate(yaml:DEPLOY_POD){node(POD_LABEL){container('kubectl'){withEnv(["REGISTRY=${REGISTRY}"]){sh '''
        set -eu
        kubectl -n dev set image deployment/conhecerse app="$REGISTRY/conhecerse:$IMAGE_TAG"
        kubectl -n dev set image deployment/conhecerse-api app="$REGISTRY/conhecerse-api:$IMAGE_TAG"
        kubectl -n dev set image deployment/conhecerse-worker app="$REGISTRY/conhecerse-api:$IMAGE_TAG"
        kubectl -n dev set image deployment/conhecerse-iam app="$REGISTRY/conhecerse-iam:$IMAGE_TAG"
        kubectl -n dev set image deployment/conhecerse-av app="$REGISTRY/conhecerse-av:$IMAGE_TAG"
        for name in conhecerse conhecerse-api conhecerse-worker conhecerse-iam conhecerse-av; do
          kubectl -n dev scale deployment/$name --replicas=1
          kubectl -n dev rollout status deployment/$name --timeout=600s
        done
        code="$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: conhecerse.lab' http://conhecerse.dev.svc.cluster.local:3000/api/ready)"
        [ "$code" = "200" ]
      '''}}}}}}
    }
    stage('DAST baseline passivo') {
      when {expression {return !params.DRY_RUN}}
      steps {script {podTemplate(yaml:DAST_POD){node(POD_LABEL){container('zap'){sh '''
        set +e
        zap-baseline.py -t http://conhecerse.lab -m 1 -T 5 -r zap-baseline.html -J zap-baseline.json
        result=$?
        set -e
        [ -s zap-baseline.html ] && [ -s zap-baseline.json ] || exit 3
        if [ "$result" -eq 1 ] || [ "$result" -gt 2 ]; then exit "$result"; fi
        if [ "$result" -eq 2 ]; then echo 'ZAP passive warnings recorded; review required before production.'; fi
      '''};archiveArtifacts artifacts:'zap-baseline.html,zap-baseline.json',allowEmptyArchive:false}}}}
    }
  }
}
