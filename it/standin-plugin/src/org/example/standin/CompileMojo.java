/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package org.example.standin;

import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

public class CompileMojo extends AbstractMojo {
    private MavenProject project;

    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Javac.compile(new File(project.getBasedir(), "src/main/java"),
                    new File(project.getBuild().getOutputDirectory()),
                    project.getCompileClasspathElements(), getLog());
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
