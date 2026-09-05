# Bring the TaskChain server console window to the foreground.
$sig = @"
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
"@
$type = Add-Type -MemberDefinition $sig -Name "Win32Front" -Namespace "U" -PassThru
$proc = Get-Process | Where-Object { $_.MainWindowTitle -like "*TaskChain*" } | Select-Object -First 1
if ($proc) {
    [void]$type::ShowWindow($proc.MainWindowHandle, 9)  # SW_RESTORE
    [void]$type::SetForegroundWindow($proc.MainWindowHandle)
    Write-Output ("brought to front: " + $proc.MainWindowTitle + " (pid " + $proc.Id + ")")
} else {
    Write-Output "server window not found"
}
