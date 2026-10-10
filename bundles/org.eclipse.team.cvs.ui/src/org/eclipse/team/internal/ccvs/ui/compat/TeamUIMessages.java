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
package org.eclipse.team.internal.ccvs.ui.compat;

import org.eclipse.osgi.util.NLS;

/** Team UI messages retained for CVS after removal from the platform. */
public final class TeamUIMessages extends NLS {
	public static String WorkingSetsDialog_Title;
	public static String WorkingSetsDialog_Message;
	public static String WorkingSetsDialog_Label;
	public static String WorkingSetsDialog_TitleBar;
	public static String WorkingSetsDialog_ErrorMessage;
	public static String ChangeLogModelProvider_13;
	public static String GenericHistoryView_ShowCommentViewer;
	public static String GenericHistoryView_ShowTagViewer;
	public static String GenericHistoryView_WrapComments;
	public static String CompareFileRevisionEditorInput_repository;
	public static String CompareFileRevisionEditorInput_workspace;
	public static String CompareFileRevisionEditorInput_localRevision;

	static {
		NLS.initializeMessages("org.eclipse.team.internal.ccvs.ui.compat.messages", TeamUIMessages.class); //$NON-NLS-1$
	}

	private TeamUIMessages() {
	}
}
