/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.apache.felix.scrplugin.ant;


import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.apache.felix.scrplugin.Options;
import org.apache.felix.scrplugin.SCRDescriptorException;
import org.apache.felix.scrplugin.SCRDescriptorFailureException;
import org.apache.felix.scrplugin.SCRDescriptorGenerator;
import org.apache.felix.scrplugin.Source;
import org.apache.felix.scrplugin.description.SpecVersion;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Location;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.resources.FileResource;


/**
 * The <code>SCRDescriptorTask</code> generates a service descriptor file based
 * on annotations found in the sources.
 */
public class SCRDescriptorTask extends MatchingTask {

    private File destdir;

    private Path classpath;

    /**
     * Name of the generated descriptor.
     */
    private String finalName = "serviceComponents.xml";

    /**
     * Name of the generated meta type file.
     */
    private String metaTypeName = "metatype.xml";

    /**
     * This flag controls the generation of the bind/unbind methods.
     */
    private boolean generateAccessors = true;

    /**
     * In strict mode the plugin even fails on warnings.
     */
    protected boolean strictMode = false;

    /**
     * Allows to define additional implementations of the interface
     * {@link org.apache.felix.scrplugin.tags.annotation.AnnotationTagProvider}
     * that provide mappings from custom annotations to
     * {@link org.apache.felix.scrplugin.tags.JavaTag} implementations. List of
     * full qualified class file names.
     *
     * @parameter
     */
    private String[] annotationTagProviders = {};

    /**
     * The version of the DS spec this plugin generates a descriptor for. By
     * default the version is detected by the used tags.
     *
     * @parameter
     */
    private String specVersion;


    @Override
    public void execute() throws BuildException {

        // ensure we know the source
        if (getImplicitFileSet().getDir() == null) {
            throw new BuildException( "srcdir attribute must be set!", getLocation());
        }

        // while debugging
        final org.apache.felix.scrplugin.Log scrLog = new AntLog( this );

        scrLog.debug( "SCRDescriptorTask Configuration" );
        scrLog.debug( "  implicitFileset: " + getImplicitFileSet() );
        scrLog.debug( "  outputDirectory: " + destdir );
        scrLog.debug( "  classpath: " + classpath );
        scrLog.debug( "  finalName: " + finalName );
        scrLog.debug( "  metaTypeName: " + metaTypeName );
        scrLog.debug( "  generateAccessors: " + generateAccessors );
        scrLog.debug( "  strictMode: " + strictMode );
        scrLog.debug( "  specVersion: " + specVersion );

        try {
            final Path classPath = createClasspath();
            final ClassLoader classLoader = getClassLoader( this.getClass().getClassLoader() );
            final org.apache.felix.scrplugin.Project project = new org.apache.felix.scrplugin.Project();
            project.setClassLoader(classLoader);
            project.setDependencies(getDependencies(classPath));
            project.setSources(getSourceFiles(getImplicitFileSet()));
            project.setClassesDirectory(destdir.getAbsolutePath());

            // create options
            final Options options = new Options();
            options.setGenerateAccessors(generateAccessors);
            options.setStrictMode(strictMode);
            options.setProperties(new HashMap<String, String>());
            options.setSpecVersion(SpecVersion.fromName(specVersion));
            if ( specVersion != null && options.getSpecVersion() == null ) {
                throw new BuildException("Unknown spec version specified: " + specVersion);
            }
            options.setAnnotationProcessors(annotationTagProviders);

            final SCRDescriptorGenerator generator = new SCRDescriptorGenerator( scrLog );

            // setup from plugin configuration
            generator.setOutputDirectory(destdir);
            generator.setOptions(options);
            generator.setProject(project);
            generator.setFinalName(finalName);
            generator.setMetaTypeName(metaTypeName);

            generator.execute();
        } catch ( final SCRDescriptorException sde ) {
            if ( sde.getSourceLocation() != null )  {
                Location loc = new Location( sde.getSourceLocation(), sde.getLineNumber(), 0 );
                throw new BuildException( sde.getMessage(), sde.getCause(), loc );
            }
            throw new BuildException( sde.getMessage(), sde.getCause() );
        } catch ( SCRDescriptorFailureException sdfe ) {
            throw new BuildException( sdfe.getMessage(), sdfe.getCause() );
        }
    }

    protected Collection<Source> getSourceFiles(final FileSet sourceFiles) {
        final String prefix = sourceFiles.getDir().getAbsolutePath();
        final int prefixLength = prefix.length() + 1;

        final List<Source> result = new ArrayList<Source>();
        @SuppressWarnings("unchecked")
        final Iterator<Resource> resources = sourceFiles.iterator();
        while ( resources.hasNext() ) {
            final Resource r = resources.next();
            if ( r instanceof FileResource ) {
                final File file = ( ( FileResource ) r ).getFile();
                if ( file.getName().endsWith(".java") ) {
                    result.add(new Source() {

                        public File getFile() {
                            return file;
                        }

                        public String getClassName() {
                            String name = file.getAbsolutePath().substring(prefixLength).replace(File.separatorChar, '/').replace('/', '.');
                            return name.substring(0, name.length() - 5);
                        }
                    });
                }
            }
        }

        return result;
    }


    private List<File> getDependencies(final Path classPath) {
        ArrayList<File> files = new ArrayList<File>();
        for ( String entry : classPath.list() ) {
            File file = new File( entry );
            if ( file.isFile() ) {
                files.add( file );
            }
        }
        return files;
    }

    private ClassLoader getClassLoader( final ClassLoader parent ) throws BuildException {
        Path classPath = createClasspath();
        log( "Using classes from: " + classPath, Project.MSG_DEBUG );
        return getProject().createClassLoader( parent, classpath );
    }


    // ---------- setters for configuration fields

    public Path createClasspath() {
        if ( this.classpath == null ) {
            this.classpath = new Path( getProject() );
        }
        return this.classpath;
    }


    public void setClasspath( Path classPath ) {
        createClasspath().add( classPath );
    }


    public void setClasspathRef( Reference classpathRef ) {
        if ( classpathRef != null && classpathRef.getReferencedObject() instanceof Path ) {
            createClasspath().add( ( Path ) classpathRef.getReferencedObject() );
        }
    }


    public void setSrcdir( File srcdir )  {
        getImplicitFileSet().setDir( srcdir );
    }


    public void setDestdir( File outputDirectory ) {
        this.destdir = outputDirectory;
        if ( destdir != null ) {
            Path dst = new Path( getProject() );
            dst.setLocation( destdir );
            createClasspath().add( dst );
        }
    }


    public void setFinalName( String finalName ) {
        this.finalName = finalName;
    }


    public void setMetaTypeName( String metaTypeName ) {
        this.metaTypeName = metaTypeName;
    }


    public void setGenerateAccessors( boolean generateAccessors ) {
        this.generateAccessors = generateAccessors;
    }


    public void setStrictMode( boolean strictMode ) {
        this.strictMode = strictMode;
    }


    public void setAnnotationTagProviders( String[] annotationTagProviders ) {
        this.annotationTagProviders = annotationTagProviders;
    }


    public void setSpecVersion( String specVersion ) {
        this.specVersion = specVersion;
    }

}
