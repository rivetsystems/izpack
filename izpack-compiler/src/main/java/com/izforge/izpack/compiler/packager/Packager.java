/*
 * $Id$
 * IzPack - Copyright 2001-2008 Julien Ponge, All Rights Reserved.
 * 
 * http://izpack.org/
 * http://izpack.codehaus.org/
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.izforge.izpack.compiler.packager;

import com.izforge.izpack.adaptator.IXMLElement;
import com.izforge.izpack.adaptator.impl.XMLElementImpl;
import com.izforge.izpack.compiler.CompilerException;
import com.izforge.izpack.compiler.compressor.PackCompressor;
import com.izforge.izpack.compiler.container.CompilerContainer;
import com.izforge.izpack.compiler.data.CompilerData;
import com.izforge.izpack.compiler.stream.ByteCountingOutputStream;
import com.izforge.izpack.compiler.stream.JarOutputStream;
import com.izforge.izpack.data.Pack;
import com.izforge.izpack.data.PackFile;
import com.izforge.izpack.data.PackInfo;
import com.izforge.izpack.util.FileUtil;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Pack200;
import java.util.zip.ZipInputStream;

/**
 * The packager class. The packager is used by the compiler to put files into an installer, and
 * create the actual installer files.
 *
 * @author Julien Ponge
 * @author Chadwick McHenry
 */
public class Packager extends PackagerBase {

    /**
     * Executable zipped output stream. First to open, last to close.
     * Attention! This is our own JarOutputStream, not the java standard!
     */
    private JarOutputStream primaryJarStream;

    private CompilerData compilerData;

    /**
     * Decoration of the primary jar stream.
     * May be compressed or not depending on the compiler data.
     */
    private OutputStream outputStream;

    /**
     * The constructor.
     *
     * @throws com.izforge.izpack.compiler.CompilerException
     *
     */
    public Packager(Properties properties, CompilerData compilerData, CompilerContainer compilerContainer, PackagerListener listener, JarOutputStream jarOutputStream, PackCompressor packCompressor, OutputStream outputStream) throws CompilerException {
        super(properties, compilerContainer, listener);
        this.compilerData = compilerData;
        this.primaryJarStream = jarOutputStream;
        this.compressor = packCompressor;
        this.outputStream = outputStream;
    }

    /* (non-Javadoc)
    * @see com.izforge.izpack.compiler.packager.IPackager#createInstaller(java.io.File)
    */

    public void createInstaller() throws Exception {
        // preliminary work
        info.setInstallerBase(compilerData.getOutput().replaceAll(".jar", ""));

        packJarsSeparate = (info.getWebDirURL() != null);

        // primary (possibly only) jar. -1 indicates primary

        sendStart();

        writeInstaller();

        // Finish up. closeAlways is a hack for pack compressions other than
        // default. Some of it (e.g. BZip2) closes the slave of it also.
        // But this should not be because the jar stream should be open 
        // for the next pack. Therefore an own JarOutputStream will be used
        // which close method will be blocked.
        primaryJarStream.closeAlways();

        sendStop();
    }

    /***********************************************************************************************
     * Private methods used when writing out the installer to jar files.
     **********************************************************************************************/

    /**
     * Write skeleton installer to primary jar. It is just an included jar, except that we copy the
     * META-INF as well.
     */
    protected void writeSkeletonInstaller() throws IOException {
        sendMsg("Copying the skeleton installer", PackagerListener.MSG_VERBOSE);

        InputStream is = Packager.class.getResourceAsStream("/" + getSkeletonSubpath());
        if (is == null) {
            File skeleton = new File(CompilerData.IZPACK_HOME, getSkeletonSubpath());
            is = new FileInputStream(skeleton);
        }
        ZipInputStream inJarStream = new ZipInputStream(is);
        PackagerHelper.copyZip(inJarStream, primaryJarStream, null, alreadyWrittenFiles);
    }

    /**
     * Write an arbitrary object to primary jar.
     */
    protected void writeInstallerObject(String entryName, Object object) throws IOException {
        primaryJarStream.putNextEntry(new org.apache.tools.zip.ZipEntry(RESOURCES_PATH + entryName));
        ObjectOutputStream out = new ObjectOutputStream(primaryJarStream);
        out.writeObject(object);
        out.flush();
        primaryJarStream.closeEntry();
    }

    /**
     * Write the data referenced by URL to primary jar.
     */
    protected void writeInstallerResources() throws IOException {
        sendMsg("Copying " + installerResourceURLMap.size() + " files into installer");

        for (Map.Entry<String, URL> stringURLEntry : installerResourceURLMap.entrySet()) {
            URL url = stringURLEntry.getValue();
            InputStream in = url.openStream();

            org.apache.tools.zip.ZipEntry newEntry = new org.apache.tools.zip.ZipEntry(RESOURCES_PATH + stringURLEntry.getKey());
            long dateTime = FileUtil.getFileDateTime(url);
            if (dateTime != -1) {
                newEntry.setTime(dateTime);
            }
            primaryJarStream.putNextEntry(newEntry);

            PackagerHelper.copyStream(in, primaryJarStream);
            primaryJarStream.closeEntry();
            in.close();
        }
    }

    /**
     * Copy included jars to primary jar.
     */
    protected void writeIncludedJars() throws IOException {
        sendMsg("Merging " + includedJarURLs.size() + " jars into installer");

        for (Object[] includedJarURL : includedJarURLs) {
            InputStream is = ((URL) includedJarURL[0]).openStream();
            ZipInputStream inJarStream = new ZipInputStream(is);
            PackagerHelper.copyZip(inJarStream, primaryJarStream, (List<String>) includedJarURL[1], alreadyWrittenFiles);
        }
    }

    /**
     * Write Packs to primary jar or each to a separate jar.
     */
    protected void writePacks() throws Exception {
        final int num = packsList.size();
        sendMsg("Writing " + num + " Pack" + (num > 1 ? "s" : "") + " into installer");

        // Map to remember pack number and bytes offsets of back references
        Map<File, Object[]> storedFiles = new HashMap<File, Object[]>();

        // Pack200 files map
        Map<Integer, File> pack200Map = new HashMap<Integer, File>();
        int pack200Counter = 0;

        // Force UTF-8 encoding in order to have proper ZipEntry names.
        primaryJarStream.setEncoding("utf-8");

        // First write the serialized files and file metadata data for each pack
        // while counting bytes.

        int packNumber = 0;
        IXMLElement root = new XMLElementImpl("packs");

        for (PackInfo packInfo : packsList) {
            Pack pack = packInfo.getPack();
            pack.nbytes = 0;
            if ((pack.id == null) || (pack.id.length() == 0)) {
                pack.id = pack.name;
            }

            // create a pack specific jar if required
            // REFACTOR : Repare web installer
            // REFACTOR : Use a mergeManager for each packages that will be added to the main merger

//            if (packJarsSeparate) {
            // See installer.Unpacker#getPackAsStream for the counterpart
//                String name = baseFile.getName() + ".pack-" + pack.id + ".jar";
//                packStream = PackagerHelper.getJarOutputStream(name, baseFile.getParentFile());
//            }

            sendMsg("Writing Pack " + packNumber + ": " + pack.name, PackagerListener.MSG_VERBOSE);

            // Retrieve the correct output stream
            org.apache.tools.zip.ZipEntry entry = new org.apache.tools.zip.ZipEntry(RESOURCES_PATH + "packs/pack-" + pack.id);
            primaryJarStream.putNextEntry(entry);
            primaryJarStream.flush(); // flush before we start counting


            ByteCountingOutputStream dos = new ByteCountingOutputStream(outputStream);
            ObjectOutputStream objOut = new ObjectOutputStream(dos);

            // We write the actual pack files
            objOut.writeInt(packInfo.getPackFiles().size());

            Iterator iter = packInfo.getPackFiles().iterator();
            while (iter.hasNext()) {
                boolean addFile = !pack.loose;
                boolean pack200 = false;
                PackFile pf = (PackFile) iter.next();
                File file = packInfo.getFile(pf);

                if (file.getName().toLowerCase().endsWith(".jar") && info.isPack200Compression() && isNotSignedJar(file)) {
                    pf.setPack200Jar(true);
                    pack200 = true;
                }

                // use a back reference if file was in previous pack, and in
                // same jar
                Object[] info = storedFiles.get(file);
                if (info != null && !packJarsSeparate) {
                    pf.setPreviousPackFileRef((String) info[0], (Long) info[1]);
                    addFile = false;
                }

                objOut.writeObject(pf); // base info

                if (addFile && !pf.isDirectory()) {
                    long pos = dos.getByteCount(); // get the position

                    if (pack200) {
                        /*
                         * Warning!
                         * 
                         * Pack200 archives must be stored in separated streams, as the Pack200 unpacker
                         * reads the entire stream...
                         *
                         * See http://java.sun.com/javase/6/docs/api/java/util/jar/Pack200.Unpacker.html
                         */
                        pack200Map.put(pack200Counter, file);
                        objOut.writeInt(pack200Counter);
                        pack200Counter = pack200Counter + 1;
                    } else {
                        FileInputStream inStream = new FileInputStream(file);
                        long bytesWritten = PackagerHelper.copyStream(inStream, objOut);
                        inStream.close();
                        if (bytesWritten != pf.length()) {
                            throw new IOException("File size mismatch when reading " + file);
                        }
                    }

                    storedFiles.put(file, new Object[]{pack.id, pos});
                }

                // even if not written, it counts towards pack size
                pack.nbytes += pf.size();
            }

            // Write out information about parsable files
            objOut.writeInt(packInfo.getParsables().size());
            iter = packInfo.getParsables().iterator();
            while (iter.hasNext()) {
                objOut.writeObject(iter.next());
            }

            // Write out information about executable files
            objOut.writeInt(packInfo.getExecutables().size());
            iter = packInfo.getExecutables().iterator();
            while (iter.hasNext()) {
                objOut.writeObject(iter.next());
            }

            // Write out information about updatecheck files
            objOut.writeInt(packInfo.getUpdateChecks().size());
            iter = packInfo.getUpdateChecks().iterator();
            while (iter.hasNext()) {
                objOut.writeObject(iter.next());
            }

            // Cleanup
            objOut.flush();
            if (!compressor.useStandardCompression()) {
                outputStream.close();
            }

            primaryJarStream.closeEntry();

            // close pack specific jar if required
            if (packJarsSeparate) {
                primaryJarStream.closeAlways();
            }

            IXMLElement child = new XMLElementImpl("pack", root);
            child.setAttribute("nbytes", Long.toString(pack.nbytes));
            child.setAttribute("name", pack.name);
            if (pack.id != null) {
                child.setAttribute("id", pack.id);
            }
            root.addChild(child);

            packNumber++;
        }

        // Now that we know sizes, write pack metadata to primary jar.
        primaryJarStream.putNextEntry(new org.apache.tools.zip.ZipEntry(RESOURCES_PATH + "packs.info"));
        ObjectOutputStream out = new ObjectOutputStream(primaryJarStream);
        out.writeInt(packsList.size());

        for (PackInfo aPacksList : packsList) {
            PackInfo pack = aPacksList;
            out.writeObject(pack.getPack());
        }
        out.flush();
        primaryJarStream.closeEntry();

        // Pack200 files
        Pack200.Packer packer = createAgressivePack200Packer();
        for (Integer key : pack200Map.keySet()) {
            File file = pack200Map.get(key);
            primaryJarStream.putNextEntry(new org.apache.tools.zip.ZipEntry(RESOURCES_PATH + "packs/pack200-" + key));
            JarFile jar = new JarFile(file);
            packer.pack(jar, primaryJarStream);
            jar.close();
            primaryJarStream.closeEntry();
        }
    }

    private Pack200.Packer createAgressivePack200Packer() {
        Pack200.Packer packer = Pack200.newPacker();
        Map<String, String> m = packer.properties();
        m.put(Pack200.Packer.EFFORT, "9");
        m.put(Pack200.Packer.SEGMENT_LIMIT, "-1");
        m.put(Pack200.Packer.KEEP_FILE_ORDER, Pack200.Packer.FALSE);
        m.put(Pack200.Packer.DEFLATE_HINT, Pack200.Packer.FALSE);
        m.put(Pack200.Packer.MODIFICATION_TIME, Pack200.Packer.LATEST);
        m.put(Pack200.Packer.CODE_ATTRIBUTE_PFX + "LineNumberTable", Pack200.Packer.STRIP);
        m.put(Pack200.Packer.CODE_ATTRIBUTE_PFX + "LocalVariableTable", Pack200.Packer.STRIP);
        m.put(Pack200.Packer.CODE_ATTRIBUTE_PFX + "SourceFile", Pack200.Packer.STRIP);
        return packer;
    }

    private boolean isNotSignedJar(File file) throws IOException {
        JarFile jar = new JarFile(file);
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().startsWith("META-INF") && entry.getName().endsWith(".SF")) {
                jar.close();
                return false;
            }
        }
        jar.close();
        return true;
    }

    /**
     * ********************************************************************************************
     * Stream utilites for creation of the installer.
     * ********************************************************************************************
     */

    /* (non-Javadoc)
    * @see com.izforge.izpack.compiler.packager.IPackager#addConfigurationInformation(com.izforge.izpack.adaptator.IXMLElement)
    */
    public void addConfigurationInformation(IXMLElement data) {
        // TODO Auto-generated method stub

    }
}