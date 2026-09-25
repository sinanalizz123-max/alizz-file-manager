// Privileged file operations executed with system identity via Shizuku.
package com.alizz.filemanager.shizuku;

interface IPrivilegedService {
    boolean deleteRecursively(String path);
    boolean canWrite(String path);
    long versionCode();
}
