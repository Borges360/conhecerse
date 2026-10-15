param([ValidateSet('migrate','api','worker','web','renew-kms')][string]$Service='web')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$path=Join-Path $root 'infra\secrets\local.env'
if(-not (Test-Path -LiteralPath $path)){throw 'Execute scripts/init-local.ps1 e bootstrap-local.py primeiro.'}
Get-Content -LiteralPath $path | ForEach-Object {
  if($_ -and -not $_.StartsWith('#')){ $pair=$_.Split('=',2);[Environment]::SetEnvironmentVariable($pair[0],$pair[1],'Process') }
}
switch($Service){
  'web' { Set-Location $root; & pnpm --dir apps/web dev }
  'renew-kms' { Set-Location $root; & python scripts/bootstrap-local.py }
  'migrate' {
    Set-Location (Join-Path $root 'backend'); & .\mvnw.cmd -q -DskipTests package
    if($LASTEXITCODE -ne 0){throw 'Build falhou.'}
    & java -jar target/conhecerse-2.0.0.jar migrate
  }
  default {
    $env:WORKER_ENABLED=if($Service -eq 'worker'){'true'}else{'false'}
    $env:PORT=if($Service -eq 'worker'){'8081'}else{'8080'}
    Set-Location (Join-Path $root 'backend'); & java -jar target/conhecerse-2.0.0.jar
  }
}
if($LASTEXITCODE -ne 0){throw "Falha ao executar $Service."}
