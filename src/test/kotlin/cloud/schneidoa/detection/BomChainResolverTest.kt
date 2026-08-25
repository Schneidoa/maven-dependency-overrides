package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

class BomChainResolverTest : BasePlatformTestCase() {

    fun `test collects import scope entries declared directly in the module`() {
        val model = configureModule(
            """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>acme-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            """.trimIndent()
        )

        val chain = resolver().resolveBomChain(model, project)

        assertEquals(
            listOf(BomImport(Gav("com.example", "acme-bom", "1.0.0"), emptyList())),
            chain.imports
        )
    }

    fun `test ignores non import scope entries`() {
        val model = configureModule(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )

        assertTrue(resolver().resolveBomChain(model, project).imports.isEmpty())
    }

    /**
     * Since IDEA 2026.2, `MavenPropertyResolver.resolve` returns its input unchanged
     * unless `MavenProjectsManager.isInitialized()` - which it is not in a light test
     * fixture, nor in a real IDE before the first Maven sync finishes. The BOM keeps a
     * raw `${'$'}{...}` version, which then finds no POM under the local repository, so the
     * BOM ends up in `uncheckedBoms` and the override is reported Inconclusive rather
     * than Confirmed. That is the intended failure direction - see
     * `DependencyManagementScannerTest`'s counterpart test for the same platform change.
     */
    fun `test leaves a property based BOM version unresolved before Maven sync`() {
        val model = configureModule(
            dependencyManagementEntries = """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>acme-bom</artifactId>
                <version>${'$'}{acme.bom.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            """.trimIndent(),
            extraProperties = "<acme.bom.version>1.0.0</acme.bom.version>"
        )

        val chain = resolver().resolveBomChain(model, project)

        assertEquals(
            listOf(BomImport(Gav("com.example", "acme-bom", "${'$'}{acme.bom.version}"), emptyList())),
            chain.imports
        )
    }

    fun `test orders child imports before parent imports`() {
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>legacy-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val childFile = myFixture.addFileToProject(
            "child/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../parent/pom.xml</relativePath>
                </parent>
                <artifactId>child</artifactId>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        assertEquals(
            listOf(
                BomImport(Gav("com.example", "acme-bom", "1.0.0"), emptyList()),
                BomImport(Gav("com.example", "legacy-bom", "1.0.0"), listOf("parent"))
            ),
            chain.imports
        )
    }

    fun `test child BOM version wins over parent's same BOM at a different version`() {
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>shared-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val childFile = myFixture.addFileToProject(
            "child/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../parent/pom.xml</relativePath>
                </parent>
                <artifactId>child</artifactId>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>shared-bom</artifactId>
                            <version>2.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        assertEquals(
            listOf(
                BomImport(Gav("com.example", "shared-bom", "2.0.0"), emptyList()),
                BomImport(Gav("com.example", "shared-bom", "1.0.0"), listOf("parent"))
            ),
            chain.imports
        )
    }

    fun `test terminates instead of hanging when the parent chain is cyclic`() {
        val fileA = myFixture.addFileToProject(
            "a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../b/pom.xml</relativePath>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>module-a</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "b/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>module-a</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../a/pom.xml</relativePath>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>module-b</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        val modelA = MavenDomUtil.getMavenDomProjectModel(project, fileA.virtualFile)!!
        val chain = resolver().resolveBomChain(modelA, project)

        assertTrue(chain.imports.isEmpty())
    }

    fun `test skips file-based parent lookup when relativePath is present but empty, and falls back to repository`() {
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>legacy-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val childFile = myFixture.addFileToProject(
            "child/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath></relativePath>
                </parent>
                <artifactId>child</artifactId>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        // The sibling "parent/pom.xml" is deliberately NOT picked up: an
        // explicit-but-empty <relativePath/> means "don't do relative file
        // lookup at all" per Maven's own semantics, so guessing "../pom.xml"
        // would risk grabbing an unrelated file that happens to sit there.
        // com.example:parent:1.0.0 also doesn't exist in the local repo
        // fixture, so the repository fallback finds nothing either - only
        // the child's own import shows up.
        assertEquals(
            listOf(BomImport(Gav("com.example", "acme-bom", "1.0.0"), emptyList())),
            chain.imports
        )
    }

    fun `test falls back to local repository lookup when relativePath is empty and the parent is only resolvable there`() {
        val childFile = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>composing-bom</artifactId>
                    <version>1.0.0</version>
                    <relativePath/>
                </parent>
                <artifactId>child</artifactId>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        // composing-bom exists only in the local-repo fixture (no sibling
        // file anywhere in the test's VFS tree) and itself imports
        // legacy-bom - this is the real-world "Spring Initializr" shape:
        // <parent><relativePath/></parent> pointing at a BOM the IDE only
        // knows about via the local Maven repository. legacy-bom's
        // declaredVia names "composing-bom" - the parent-chain hop that
        // actually declares the import - even though composing-bom itself
        // was reached via repository lookup, not a sibling file.
        assertEquals(
            listOf(BomImport(Gav("com.example", "legacy-bom", "1.0.0"), listOf("composing-bom"))),
            chain.imports
        )
    }

    fun `test multi-level parent chain records every hop in declaredVia`() {
        myFixture.addFileToProject(
            "grandparent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>grandparent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>legacy-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>grandparent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../grandparent/pom.xml</relativePath>
                </parent>
                <artifactId>parent</artifactId>
                <packaging>pom</packaging>
            </project>
            """.trimIndent()
        )
        val childFile = myFixture.addFileToProject(
            "child/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../parent/pom.xml</relativePath>
                </parent>
                <artifactId>child</artifactId>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        assertEquals(
            listOf(BomImport(Gav("com.example", "legacy-bom", "1.0.0"), listOf("parent", "grandparent"))),
            chain.imports
        )
    }

    fun `test reports a parent that is not in the local repository instead of silently ending the chain`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>absent-parent</artifactId>
                    <version>7.7.7</version>
                    <relativePath/>
                </parent>
                <artifactId>consumer</artifactId>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        val chain = resolver().resolveBomChain(model, project)

        assertEquals(listOf(Gav("com.example", "absent-parent", "7.7.7")), chain.truncatedAt)
        assertTrue(chain.imports.isEmpty())
    }

    fun `test reports a parent whose POM is present but yields no model instead of silently ending the chain`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>unreadable-parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath/>
                </parent>
                <artifactId>consumer</artifactId>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        val chain = resolver().resolveBomChain(model, project)

        // The file is on disk but is not a POM (see the fixture's own comment). Left unreported,
        // this ends the walk silently and detection can go on to report Confirmed - "safe to
        // remove" - on a chain it never finished walking. RemotePomFetcher writing POMs into the
        // repository makes a partially-written or error-bodied file a real possibility here.
        assertEquals(listOf(Gav("com.example", "unreadable-parent", "1.0.0")), chain.truncatedAt)
        assertTrue(chain.imports.isEmpty())
    }

    fun `test reports a parent whose coordinate is incomplete instead of silently ending the chain`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>nameless-parent</artifactId>
                    <relativePath/>
                </parent>
                <artifactId>consumer</artifactId>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        val chain = resolver().resolveBomChain(model, project)

        // No <version>, so there is no coordinate to look up or fetch - but the walk still ended
        // early, which is the fact that must not be lost. The missing part is rendered as "?"
        // rather than guessed, so the reported coordinate says exactly what the POM declared.
        assertEquals(listOf(Gav("com.example", "nameless-parent", "?")), chain.truncatedAt)
        assertTrue(chain.imports.isEmpty())
    }

    /**
     * The relativePath analogue of the local-repository "POM present but yields no
     * model" case above: a sibling file exists at the declared <relativePath> but is
     * not a Maven POM. Before this was fixed, resolveParentViaRelativePath returned
     * null here with no report at all, and the walk fell through to the repository
     * lookup silently - reading as a fully resolved chain even though the relativePath
     * copy (the one a real Maven reactor build would actually use) was broken, and
     * whatever the repository fallback found there might be a stale, unrelated
     * previously-installed copy of the same coordinate.
     */
    fun `test reports a relativePath parent that resolves to a file but yields no model, then still falls back to the repository`() {
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <error>
                <status>404</status>
                <message>Not Found</message>
            </error>
            """.trimIndent()
        )
        val childFile = myFixture.addFileToProject(
            "child/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>composing-bom</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../parent/pom.xml</relativePath>
                </parent>
                <artifactId>child</artifactId>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        // Reported as truncated even though a chain was still found below: the
        // relativePath copy - the one that would actually be used in a real reactor
        // build - was broken, so this is not a chain that was fully walked, regardless
        // of what the repository fallback happened to find under the same coordinate.
        assertEquals(listOf(Gav("com.example", "composing-bom", "1.0.0")), chain.truncatedAt)
        assertEquals(
            listOf(BomImport(Gav("com.example", "legacy-bom", "1.0.0"), listOf("composing-bom"))),
            chain.imports
        )
    }

    private fun resolver(): BomChainResolver {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return BomChainResolver(File(fixtureUrl.toURI()))
    }

    private fun configureModule(
        dependencyManagementEntries: String,
        extraProperties: String = ""
    ): MavenDomProjectModel {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>

                <properties>
                    $extraProperties
                </properties>

                <dependencyManagement>
                    <dependencies>
                        $dependencyManagementEntries
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        return MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
    }
}
