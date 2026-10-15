param([switch]$Start)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$secretDir = Join-Path $root 'infra\secrets'
New-Item -ItemType Directory -Force -Path $secretDir | Out-Null
if ($IsWindows -or $env:OS -eq 'Windows_NT') {
  $acl = Get-Acl -LiteralPath $secretDir
  $acl.SetAccessRuleProtection($true, $false)
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
  $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
  $acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $secretDir -AclObject $acl
}
$path = Join-Path $secretDir 'local.env'
if (-not (Test-Path -LiteralPath $path)) {
  function New-LocalSecret { $bytes = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($bytes); return [Convert]::ToBase64String($bytes) }
  $values = @{
    POSTGRES_ADMIN_PASSWORD = (New-LocalSecret); DB_PASSWORD = (New-LocalSecret)
    MIGRATOR_PASSWORD = (New-LocalSecret); WEB_DB_PASSWORD = (New-LocalSecret); IAM_DB_PASSWORD = (New-LocalSecret)
    IAM_ADMIN_PASSWORD = (New-LocalSecret); OIDC_CLIENT_SECRET = (New-LocalSecret); BFF_ENCRYPTION_KEY = (New-LocalSecret)
    LOOKUP_HMAC_KEY = (New-LocalSecret); MINIO_ROOT_USER = 'conhecerse-local'; MINIO_ROOT_PASSWORD = (New-LocalSecret)
    APP_ORIGIN = 'http://localhost:3000'; APP_ENV = 'local'; OIDC_CLIENT_ID = 'conhecerse-web'
    PUBLIC_URL = 'http://localhost:3000'; BIND_ADDRESS = '127.0.0.1'
    OIDC_ISSUER = 'http://localhost:8180/realms/conhecerse'; API_BASE_URL = 'http://127.0.0.1:8080'
    DB_URL = 'jdbc:postgresql://localhost:15432/conhecerse'; DB_USER = 'conhecerse_runtime'; MIGRATOR_USER = 'conhecerse_migrator'
    COOKIE_SECURE = 'false'; BAO_URL = 'http://localhost:8200'; BAO_TOKEN_FILE = (Join-Path $secretDir 'bao-token')
    S3_ENDPOINT = 'http://localhost:9000'; S3_PUBLIC_ENDPOINT = 'http://localhost:9000'; S3_BUCKET = 'conhecerse-private'
    S3_REGION = 'sa-east-1'; CLAMAV_HOST = 'localhost'; CLAMAV_PORT = '3310'
  }
  $values.BFF_DATABASE_URL = 'postgresql://conhecerse_web:'+[uri]::EscapeDataString($values.WEB_DB_PASSWORD)+'@localhost:15432/conhecerse'
  [IO.File]::WriteAllLines($path, ($values.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }), (New-Object Text.UTF8Encoding($false)))
}
Write-Output 'Configuração local preservada em infra/secrets/local.env (sem exibir valores).'
if ($Start) {
  & docker compose --env-file $path -f (Join-Path $root 'infra\local\compose.yaml') up -d
  if ($LASTEXITCODE -ne 0) { throw 'Dependências locais não iniciaram.' }
}
