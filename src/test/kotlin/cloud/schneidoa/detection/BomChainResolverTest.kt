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
            chain
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

        assertTrue(resolver().resolveBomChain(model, project).isEmpty())
    }

    fun `test resolves a property based BOM version`() {
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
            listOf(BomImport(Gav("com.example", "acme-bom", "1.0.0"), emptyList())),
            chain
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
            chain
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
            chain
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

        assertTrue(chain.isEmpty())
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
            chain
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
            chain
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
            chain
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
