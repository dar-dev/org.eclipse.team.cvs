/*******************************************************************************
 * Copyright (c) 2000, 2017 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ccvs.core.streams;

import org.eclipse.osgi.util.NLS;

/** Messages retained with the former Team Core stream helpers. */
final class Messages extends NLS {
	public static String PollingInputStream_readTimeout;
	public static String PollingInputStream_closeTimeout;
	public static String PollingOutputStream_writeTimeout;
	public static String PollingOutputStream_closeTimeout;
	public static String TimeoutOutputStream_cannotWriteToStream;

	static {
		NLS.initializeMessages("org.eclipse.team.internal.ccvs.core.streams.messages", Messages.class); //$NON-NLS-1$
	}

	private Messages() {
	}
}
