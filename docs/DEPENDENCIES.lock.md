# Dependências fixadas

Complementação ADR-017: `org.bouncycastle:bcpkix-jdk18on:1.86` (com bcprov/bcutil transitivos da mesma versão), para CMS/CAdES e verificação criptográfica de PDFs. Referência primária: [distribuição oficial](https://www.bouncycastle.org/download/bouncy-castle-java/). Os arquivos de cadeia/CRL e a homologação ICP-Brasil são fornecidos pelo operador e não fazem parte do artefato de código.

As versões exatas ficam em `pnpm-lock.yaml`, `backend/pom.xml`, `.mvn/wrapper/maven-wrapper.properties`, `infra/images/images.lock.json`, `.terraform.lock.hcl` e nos SHAs dos Actions. Os locks são artefatos versionados; não usar latest.

| Grupo | Versão | Licença upstream / observação |
| --- | --- | --- |
| Java / Spring Boot / Modulith |21 /4.1.1 /2.1.1|OpenJDK GPL+Classpath; Spring Apache2 |
| Maven / wrapper |3.9.16 /3.3.4|Apache2; distribuição verificada por checksum |
| PostgreSQL / Flyway |17.11 /BOM Spring|PostgreSQL License /Apache2 |
| Next / React / TypeScript |16.4.0 /19.3.0 /5.9.3|MIT /MIT /Apache2 |
| Node.js |24.21.0|MIT; runtime sem npm/yarn; distribuição Windows verificada por SHA256 |
| Zod / pnpm |4.6.7 /10.28.2|MIT |
| Playwright / axe |1.64.0 /4.13.0|Apache2 /MPL2 |
| Keycloak |26.8.1|Apache2; realm importado e login/MFA reais testados |
| OpenBao |2.7.1|MPL2; transit não exportável |
| LiveKit server / client |1.13.9 /2.22.4|Apache2; mídia ainda depende de homologação |
| ClamAV |1.5.4|GPL2; validar distribuição e atualização de assinaturas |
| MinIO local |RELEASE.2025-10-15T17-29-55Z, compilado da fonte|AGPL3; upstream arquivado, avaliar suporte e SCA antes de uso operacional |
| PDFBox / AWS S3 / jsoup |3.0.8 /2.55.16 /1.23.2|Apache2 /Apache2 /MIT |
| Montserrat / Playfair Display |fontsource5.3.0|OFL1.1; arquivos e fontes locais |
| noble/hashes |2.4.0|MIT; checksum no HTTP da intranet |
| OpenTofu / AWS provider |1.13.1 /6.8.0|MPL2 |

Registrar SBOM e revisão de licenças/CVEs da release efetivamente construída. Este inventário não constitui parecer de licenciamento. O Dockerfile MinIO compila a fonte oficial fixada porque a imagem comunitária histórica deixou de estar disponível; não foi usado um tag inexistente como dependência funcional. Imagens de aplicação ainda precisam de build, scan e assinatura no ambiente autorizado.

Patch de segurança: Spring Framework7.0.9, Jackson3.2.3 (Jackson2.22.3 quando transitivo), Tomcat11.0.25, Netty4.2.17.Final e PostgreSQL JDBC42.7.13. Validação SCA registrada no relatório de execução.
