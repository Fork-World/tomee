/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.tck.impl;

import javax.ejb.EJBException;
import javax.ejb.embeddable.EJBContainer;
import javax.validation.ValidationException;
import org.apache.openejb.OpenEJB;
import org.apache.openejb.assembler.classic.Assembler;
import org.apache.openejb.cdi.ThreadSingletonServiceImpl;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.tck.OpenEJBTckDeploymentRuntimeException;
import org.apache.openejb.tck.util.ZipUtil;
import org.apache.openejb.util.SetAccessible;
import org.apache.webbeans.config.WebBeansContext;
import org.jboss.testharness.api.DeploymentException;
import org.jboss.testharness.impl.packaging.Resource;
import org.jboss.testharness.impl.packaging.Resources;
import org.jboss.testharness.impl.packaging.ear.EjbJarXml;
import org.jboss.testharness.impl.packaging.ear.PersistenceXml;
import org.jboss.testharness.impl.packaging.jsr303.ValidationXml;
import org.jboss.testharness.spi.Containers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * @version $Rev$ $Date$
 */
public class ContainersImpl implements Containers {

    private static String stuck;

    private Exception exception;
    private EJBContainer container;
    private ClassLoader originalClassLoader;

    @Override
    public boolean deploy(InputStream archive, String name) {
        if (!OpenEJB.isInitialized()) stuck = name;
        else System.out.println("STUCK " + stuck);

        exception = null;

        final Assembler assembler = SystemInstance.get().getComponent(Assembler.class);

        ThreadSingletonServiceImpl.exit(null);
        if (assembler != null) {
            assembler.destroy();
        }
        try {
            final File file = writeToFile(archive, name);
            final Map<String, Object> map = new HashMap<String, Object>();
            map.put(EJBContainer.MODULES, file);
            map.put(EJBContainer.APP_NAME, name);

            originalClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(new URLClassLoader(new URL[]{ file.toURI().toURL() }, originalClassLoader));
            container = EJBContainer.createEJBContainer(map);

            final WebBeansContext webBeansContext = ThreadSingletonServiceImpl.get();
            dump(webBeansContext.getBeanManagerImpl());
        } catch (Exception e) {
            if (e instanceof EJBException && e.getCause() instanceof ValidationException) {
                exception = ValidationException.class.cast(e.getCause());
            } else {
                exception = e;
            }
            return false;
        }

        return true;
    }

    private void dump(Object o) {
        try {
            final Class<?> clazz = o.getClass();

            for (Field field : clazz.getDeclaredFields()) {
                SetAccessible.on(field);

                if (Collection.class.isAssignableFrom(field.getType())) {
                    final Collection collection = (Collection) field.get(o);
                    System.out.println(field.getName() + "\t= " + collection.size());
                }
            }
        } catch (Exception e) {
            // no-op
        }
    }

    private URL getResource(Class clazz, String path) {
        final String resourcePath = clazz.getPackage().getName().replace(".", "/") + "/" + path;

        return clazz.getClassLoader().getResource(resourcePath);
    }


    public static void main(String[] args) throws IOException {
        new ContainersImpl().memoryMappedFile();

        System.out.println();
    }

    public void memoryMappedFile() throws IOException {

        FileChannel rwChannel = new RandomAccessFile(new File("/tmp/memory-mapped.txt"), "rw").getChannel();

        final byte[] bytes = "hello world".getBytes();

        ByteBuffer writeonlybuffer = rwChannel.map(FileChannel.MapMode.READ_WRITE, 0, bytes.length);
        writeonlybuffer.put(bytes);
        writeonlybuffer.compact();
    }

    private File writeToFile(InputStream archive, String name) throws IOException {
        final File file = File.createTempFile("deploy", "-" + name);
        file.deleteOnExit();

        try {

            Map<String, URL> resources = new HashMap<String, URL>();

            final Class<?> clazz = this.getClass().getClassLoader().loadClass(name.replace(".jar", ""));

            if (clazz.isAnnotationPresent(EjbJarXml.class)) {
                final URL resource = getResource(clazz, clazz.getAnnotation(EjbJarXml.class).value());

                if (resource != null) resources.put("META-INF/ejb-jar.xml", resource);
            }

            if (clazz.isAnnotationPresent(PersistenceXml.class)) {
                final URL resource = getResource(clazz, clazz.getAnnotation(PersistenceXml.class).value());

                if (resource != null) resources.put("META-INF/persistence.xml", resource);
            }

            if (clazz.isAnnotationPresent(ValidationXml.class)) {
                String path = clazz.getAnnotation(ValidationXml.class).value();
                if (path.contains(".jar")) {
                    path = path.substring(path.indexOf( "!" ) + 2);
                }

                final URL resource = getResource(clazz, path);
                if (resource != null) {
                    resources.put("META-INF/validation.xml", resource);
                } else {
                    throw new OpenEJBTckDeploymentRuntimeException("can't find validation descriptor file " + path);
                }
            }

            if (clazz.isAnnotationPresent(Resource.class)) {
                Resource resourceAnn = clazz.getAnnotation(Resource.class);
                final URL resource = getResource(clazz, resourceAnn.source());
                if (resource != null) {
                    resources.put(resourceAnn.destination().replaceFirst("WEB-INF/classes/", ""), resource);
                }
            }

            if (clazz.isAnnotationPresent(Resources.class)) {
                Resources resourcesAnn = clazz.getAnnotation(Resources.class);
                for (Resource resourceAnn : resourcesAnn.value()) {
                    final URL resource = getResource(clazz, resourceAnn.source());
                    if (resource != null) {
                        resources.put(resourceAnn.destination().replaceFirst("WEB-INF/classes/", ""), resource);
                    }
                }
            }

            final boolean isJar = name.endsWith(".jar");

            final ZipInputStream zin = new ZipInputStream(archive);
            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(524288);
            final ZipOutputStream zout = new ZipOutputStream(byteArrayOutputStream);

            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                String entryName = entry.getName();

                if (isJar && entryName.startsWith("WEB-INF/classes/")) {
                    entryName = entryName.replaceFirst("WEB-INF/classes/", "");
                }

                InputStream src = zin;

                if (resources.containsKey(entryName)) {
                    src = resources.get(entryName).openStream();
                }
                resources.remove(entryName);

                zout.putNextEntry(new ZipEntry(entryName));
                ZipUtil.copy(src, zout);
            }

            for (Map.Entry<String, URL> entry : resources.entrySet()) {
                zout.putNextEntry(new ZipEntry(entry.getKey()));
                InputStream in = entry.getValue().openStream();
                ZipUtil.copy(in, zout);
                in.close();
            }

            if (System.getProperty("force.deployment") != null && !resources.containsKey("META-INF/beans.xml")) {
                zout.putNextEntry(new ZipEntry("META-INF/beans.xml"));
                final InputStream in = new ByteArrayInputStream("<beans />".getBytes());
                ZipUtil.copy(in, zout);
                in.close();
            }

            close(zin);
            close(zout);

            writeToFile(file, byteArrayOutputStream);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return file;
    }

    private void writeToFile(File file, ByteArrayOutputStream byteArrayOutputStream) throws IOException {
        final byte[] bytes = byteArrayOutputStream.toByteArray();

        final FileOutputStream fileOutputStream = new FileOutputStream(file);
        fileOutputStream.write(bytes);
        fileOutputStream.close();
    }

    public static void close(Closeable closeable) throws IOException {
        if (closeable == null) return;
        try {
            if (closeable instanceof Flushable) {
                ((Flushable) closeable).flush();
            }
        } catch (IOException e) {
            // no-op
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // no-op
        }
    }


    @Override
    public DeploymentException getDeploymentException() {
        return new DeploymentException(exception.getLocalizedMessage(), exception);
    }

    @Override
    public void undeploy(String name) {
        if (container != null) {
            container.close();
        }
        if (originalClassLoader != null) {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public void setup() throws IOException {
    }

    @Override
    public void cleanup() throws IOException {
    }
}
