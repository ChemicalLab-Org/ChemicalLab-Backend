param(
    [Parameter(Mandatory)][string]$FixtureDirectory,
    [Parameter(Mandatory)][ValidateSet('authenticate','propose','approve','apply')][string]$Action,
    [long]$Evaluation,
    [long]$Proposal,
    [ValidateSet('PLATFORM','INSTITUTION')][string]$ApprovalRole,
    [string]$Approver,
    [string]$SessionToken,
    [string]$Checksum,
    [string]$Evidence
)
$ErrorActionPreference = 'Stop'
$directory = (Resolve-Path -LiteralPath $FixtureDirectory).Path
$ready = Get-Content -LiteralPath (Join-Path $directory 'ready.json') -Raw | ConvertFrom-Json
$origin = [Uri]$ready.origin
if ($origin.Scheme -ne 'http' -or $origin.Host -ne '127.0.0.1') { throw 'The approver login requires the local fixture.' }
if ($Action -eq 'authenticate') {
    if (!$Approver) { throw 'Specify the approver login before creating the proposal.' }
    $loginBody = @{ usernameOrEmail = $Approver; password = $ready.password } | ConvertTo-Json -Compress
    $session = Invoke-RestMethod -Method Post -Uri ([Uri]::new($origin, '/api/auth/login')) -ContentType 'application/json' -Body $loginBody
    if (!$session.token) { throw 'The fixture login did not return a session.' }
    return $session.token
}
$inbox = Join-Path $directory 'request.json'
$outbox = Join-Path $directory 'response.json'
if ((Test-Path -LiteralPath $inbox) -or (Test-Path -LiteralPath $outbox)) { throw 'The previous command is still pending.' }
$body = @{ action = $Action }
switch ($Action) {
    'propose' {
        if ($Evaluation -le 0) { throw 'Specify the assigned evaluation.' }
        $body.evaluation = $Evaluation
    }
    'approve' {
        if ($Proposal -le 0 -or !$ApprovalRole -or !$SessionToken -or $Checksum -notmatch '^[a-f0-9]{32}$' -or
            $Evidence -notmatch '^FIXTURE-[A-Z0-9_-]{1,80}$') {
            throw 'Specify the proposal, role, existing approver session, reviewed checksum and fixture evidence.'
        }
        $body += @{ proposal = $Proposal; role = $ApprovalRole; token = $SessionToken
            checksum = $Checksum; evidence = $Evidence }
    }
    'apply' {
        if ($Proposal -le 0) { throw 'Specify the approved proposal.' }
        $body.proposal = $Proposal
    }
}
$temp = Join-Path $directory 'request.tmp'
[IO.File]::WriteAllText($temp, ($body | ConvertTo-Json -Compress))
Move-Item -LiteralPath $temp -Destination $inbox
$deadline = [DateTime]::UtcNow.AddSeconds(75)
while (!(Test-Path -LiteralPath $outbox) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 200 }
if (!(Test-Path -LiteralPath $outbox)) { throw 'The local fixture did not return a receipt.' }
$receipt = Get-Content -LiteralPath $outbox -Raw | ConvertFrom-Json
Remove-Item -LiteralPath $outbox
if ($receipt.error) { throw "Fixture command rejected ($($receipt.error))." }
$receipt | ConvertTo-Json -Depth 30
