param([int]$JavaId, [string]$PostgresIds)
$ids = @($JavaId) + @($PostgresIds.Split(',') | ForEach-Object { [int]$_ })
while ($true) {
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $values = @(Get-Process -Id $ids -ErrorAction SilentlyContinue | ForEach-Object {
        @{ id=$_.Id; cpuMs=$_.TotalProcessorTime.TotalMilliseconds; rssBytes=$_.WorkingSet64 }
    })
    ConvertTo-Json -InputObject @{ epochMs=$now; java=$JavaId; processes=$values } -Depth 4 -Compress
    Start-Sleep -Milliseconds 500
}
