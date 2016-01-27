/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.util;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.util.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.security.UserGroupInformation;

/**
 * Utility class that gives access to the execution environment of the JVM, like
 * the executing user, startup options, or the JVM version.
 */
public class EnvironmentInformation {

	private static final Logger LOG = LoggerFactory.getLogger(EnvironmentInformation.class);

	public static final String UNKNOWN = "<unknown>";

	/**
	 * Returns the version of the code as String. If version == null, then the JobManager does not run from a
	 * Maven build. An example is a source code checkout, compile, and run from inside an IDE.
	 * 
	 * @return The version string.
	 */
	public static String getVersion() {
		String version = EnvironmentInformation.class.getPackage().getImplementationVersion();
		return version != null ? version : UNKNOWN;
	}

	/**
	 * Returns the code revision (commit and commit date) of Flink, as generated by the Maven builds.
	 * 
	 * @return The code revision.
	 */
	public static RevisionInformation getRevisionInformation() {
		String revision = UNKNOWN;
		String commitDate = UNKNOWN;
		try (InputStream propFile = EnvironmentInformation.class.getClassLoader().getResourceAsStream(".version.properties")) {
			if (propFile != null) {
				Properties properties = new Properties();
				properties.load(propFile);
				String propRevision = properties.getProperty("git.commit.id.abbrev");
				String propCommitDate = properties.getProperty("git.commit.time");
				revision = propRevision != null ? propRevision : UNKNOWN;
				commitDate = propCommitDate != null ? propCommitDate : UNKNOWN;
			}
		} catch (Throwable t) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Cannot determine code revision: Unable to read version property file.", t);
			} else {
				LOG.info("Cannot determine code revision: Unable to read version property file.");
			}
		}
		
		return new RevisionInformation(revision, commitDate);
	}

	/**
	 * Gets the name of the user that is running the JVM.
	 * 
	 * @return The name of the user that is running the JVM.
	 */
	public static String getUserRunning() {
		try {
			return UserGroupInformation.getCurrentUser().getShortUserName();
		}
		catch (LinkageError e) {
			// hadoop classes are not in the classpath
			LOG.debug("Cannot determine user/group information using Hadoop utils. " +
					"Hadoop classes not loaded or compatible", e);
		}
		catch (Throwable t) {
			// some other error occurred that we should log and make known
			LOG.warn("Error while accessing user/group information via Hadoop utils.", t);
		}
		
		String user = System.getProperty("user.name");
		if (user == null) {
			user = UNKNOWN;
			if (LOG.isDebugEnabled()) {
				LOG.debug("Cannot determine user/group information for the current user.");
			}
		}
		return user;
	}

	/**
	 * The maximum JVM heap size, in bytes.
	 * 
	 * @return The maximum JVM heap size, in bytes.
	 */
	public static long getMaxJvmHeapMemory() {
		long maxMemory = Runtime.getRuntime().maxMemory();

		if (maxMemory == Long.MAX_VALUE) {
			// amount of free memory unknown
			try {
				// workaround for Oracle JDK
				OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
				Class<?> clazz = Class.forName("com.sun.management.OperatingSystemMXBean");
				Method method = clazz.getMethod("getTotalPhysicalMemorySize");
				maxMemory = (Long) method.invoke(operatingSystemMXBean) / 4;
			}
			catch (Throwable e) {
				throw new RuntimeException("Could not determine the amount of free memory.\n" +
						"Please set the maximum memory for the JVM, e.g. -Xmx512M for 512 megabytes.");
			}
		}
		
		return maxMemory;
	}

	/**
	 * Gets an estimate of the size of the free heap memory.
	 * 
	 * NOTE: This method is heavy-weight. It triggers a garbage collection to reduce fragmentation and get
	 * a better estimate at the size of free memory. It is typically more accurate than the plain version
	 * {@link #getSizeOfFreeHeapMemory()}.
	 * 
	 * @return An estimate of the size of the free heap memory, in bytes.
	 */
	public static long getSizeOfFreeHeapMemoryWithDefrag() {
		// trigger a garbage collection, to reduce fragmentation
		System.gc();
		
		return getSizeOfFreeHeapMemory();
	}
	
	/**
	 * Gets an estimate of the size of the free heap memory. The estimate may vary, depending on the current
	 * level of memory fragmentation and the number of dead objects. For a better (but more heavy-weight)
	 * estimate, use {@link #getSizeOfFreeHeapMemoryWithDefrag()}.
	 * 
	 * @return An estimate of the size of the free heap memory, in bytes.
	 */
	public static long getSizeOfFreeHeapMemory() {
		Runtime r = Runtime.getRuntime();
		long maxMemory = r.maxMemory();

		if (maxMemory == Long.MAX_VALUE) {
			// amount of free memory unknown
			try {
				// workaround for Oracle JDK
				OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
				Class<?> clazz = Class.forName("com.sun.management.OperatingSystemMXBean");
				Method method = clazz.getMethod("getTotalPhysicalMemorySize");
				maxMemory = (Long) method.invoke(operatingSystemMXBean) / 4;
			} catch (Throwable e) {
				throw new RuntimeException("Could not determine the amount of free memory.\n" +
						"Please set the maximum memory for the JVM, e.g. -Xmx512M for 512 megabytes.");
			}
		}

		return maxMemory - r.totalMemory() + r.freeMemory();
	}

	/**
	 * Gets the version of the JVM in the form "VM_Name - Vendor  - Spec/Version".
	 *
	 * @return The JVM version.
	 */
	public static String getJvmVersion() {
		try {
			final RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
			return bean.getVmName() + " - " + bean.getVmVendor() + " - " + bean.getSpecVersion() + '/' + bean.getVmVersion();
		}
		catch (Throwable t) {
			return UNKNOWN;
		}
	}

	/**
	 * Gets the system parameters and environment parameters that were passed to the JVM on startup.
	 *
	 * @return The options passed to the JVM on startup.
	 */
	public static String getJvmStartupOptions() {
		try {
			final RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
			final StringBuilder bld = new StringBuilder();
			
			for (String s : bean.getInputArguments()) {
				bld.append(s).append(' ');
			}

			return bld.toString();
		}
		catch (Throwable t) {
			return UNKNOWN;
		}
	}

	/**
	 * Gets the system parameters and environment parameters that were passed to the JVM on startup.
	 *
	 * @return The options passed to the JVM on startup.
	 */
	public static String[] getJvmStartupOptionsArray() {
		try {
			RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
			List<String> options = bean.getInputArguments();
			return options.toArray(new String[options.size()]);
		}
		catch (Throwable t) {
			return new String[0];
		}
	}

	/**
	 * Gets the directory for temporary files, as returned by the JVM system property "java.io.tmpdir".
	 *
	 * @return The directory for temporary files.
	 */
	public static String getTemporaryFileDirectory() {
		return System.getProperty("java.io.tmpdir");
	}

	/**
	 * Tries to retrieve the maximum number of open file handles. This method will only work on
	 * UNIX-based operating systems with Sun/Oracle Java versions.
	 * 
	 * <p>If the number of max open file handles cannot be determined, this method returns {@code -1}.</p>
	 * 
	 * @return The limit of open file handles, or {@code -1}, if the limit could not be determined.
	 */
	public static long getOpenFileHandlesLimit() {
		Class<?> sunBeanClass;
		try {
			sunBeanClass = Class.forName("com.sun.management.UnixOperatingSystemMXBean");
		}
		catch (ClassNotFoundException e) {
			return -1L;
		}
		
		try {
			Method fhLimitMethod = sunBeanClass.getMethod("getMaxFileDescriptorCount");
			Object result = fhLimitMethod.invoke(ManagementFactory.getOperatingSystemMXBean());
			return (Long) result;
		}
		catch (Throwable t) {
			LOG.warn("Unexpected error when accessing file handle limit", t);
			return -1L;
		}
	}
	
	/**
	 * Logs a information about the environment, like code revision, current user, java version,
	 * and JVM parameters.
	 *
	 * @param log The logger to log the information to.
	 * @param componentName The component name to mention in the log.
	 * @param commandLineArgs The arguments accompanying the starting the component.
	 */
	public static void logEnvironmentInfo(Logger log, String componentName, String[] commandLineArgs) {
		if (log.isInfoEnabled()) {
			RevisionInformation rev = getRevisionInformation();
			String version = getVersion();
			
			String user = getUserRunning();
			
			String jvmVersion = getJvmVersion();
			String[] options = getJvmStartupOptionsArray();
			
			String javaHome = System.getenv("JAVA_HOME");
			
			long maxHeapMegabytes = getMaxJvmHeapMemory() >>> 20;
			
			log.info("--------------------------------------------------------------------------------");
			log.info(" Starting " + componentName + " (Version: " + version + ", "
					+ "Rev:" + rev.commitId + ", " + "Date:" + rev.commitDate + ")");
			log.info(" Current user: " + user);
			log.info(" JVM: " + jvmVersion);
			log.info(" Maximum heap size: " + maxHeapMegabytes + " MiBytes");
			log.info(" JAVA_HOME: " + (javaHome == null ? "(not set)" : javaHome));
			log.info(" Hadoop version: " + VersionInfo.getVersion());

			if (options.length == 0) {
				log.info(" JVM Options: (none)");
			}
			else {
				log.info(" JVM Options:");
				for (String s: options) {
					log.info("    " + s);
				}
			}

			if (commandLineArgs == null || commandLineArgs.length == 0) {
				log.info(" Program Arguments: (none)");
			}
			else {
				log.info(" Program Arguments:");
				for (String s: commandLineArgs) {
					log.info("    " + s);
				}
			}

			log.info(" Classpath: " + System.getProperty("java.class.path"));

			log.info("--------------------------------------------------------------------------------");
		}
	}

	// --------------------------------------------------------------------------------------------

	/** Don't instantiate this class */
	private EnvironmentInformation() {}

	// --------------------------------------------------------------------------------------------

	/**
	 * Revision information encapsulates information about the source code revision of the Flink
	 * code.
	 */
	public static class RevisionInformation {
		
		/** The git commit id (hash) */
		public final String commitId;
		
		/** The git commit date */
		public final String commitDate;

		public RevisionInformation(String commitId, String commitDate) {
			this.commitId = commitId;
			this.commitDate = commitDate;
		}
	}
}
