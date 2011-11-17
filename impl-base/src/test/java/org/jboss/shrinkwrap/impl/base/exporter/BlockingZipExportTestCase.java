/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shrinkwrap.impl.base.exporter;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;

import org.jboss.shrinkwrap.api.ArchiveFactory;
import org.jboss.shrinkwrap.api.ConfigurationBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;

/**
 *
 * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
 */
public class BlockingZipExportTestCase {

    /**
     * SHRINKWRAP-279
     */
    @Test
    public void testExecutor() throws InterruptedException, IOException {
        ConfigurationBuilder builder = new ConfigurationBuilder()
            .executorService(Executors.newSingleThreadExecutor());
        ArchiveFactory factory = ShrinkWrap.createDomain(builder).getArchiveFactory();
        System.out.println("1");

        // blow the pipe
        factory.create(JavaArchive.class, "test.jar")
            .add(MegaByteAsset.newInstance(), "dummy")
            .as(ZipExporter.class)
            .exportAsInputStream();

        System.out.println("2");
        InputStream in2 = factory.create(JavaArchive.class, "test2.jar")
            .add(MegaByteAsset.newInstance(), "dummy")
            .as(ZipExporter.class)
            .exportAsInputStream();
        System.out.println("3");
        in2.read();
        System.out.println("4");
    }
}
