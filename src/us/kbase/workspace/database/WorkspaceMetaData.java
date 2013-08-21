package us.kbase.workspace.database;

import java.util.Date;

public interface WorkspaceMetaData {
	
	public int getId();
	public String getName();
	public String getOwner();
	public Date getModDate();
	public Date getDeletedDate();
	public Permission getUserPermission();
	public boolean isGloballyReadable();
}
