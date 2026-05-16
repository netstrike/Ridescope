<?php
declare(strict_types=1);

/*
 * Cambia questa password prima di usare lo script.
 * L'app deve inviarla tramite header X-Upload-Password
 * oppure tramite il campo POST "password".
 */
$authPassword = getenv('RIDESCOPE_UPLOAD_PASSWORD') ?: '';

$destinationDir = __DIR__
    . DIRECTORY_SEPARATOR . 'ridescope'
    . DIRECTORY_SEPARATOR . 'web'
    . DIRECTORY_SEPARATOR . 'debug';

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
    jsonResponse(405, [
        'ok' => false,
        'error' => 'Use POST multipart/form-data.',
    ]);
}

if ($authPassword === '') {
    jsonResponse(500, [
        'ok' => false,
        'error' => 'Upload password is not configured.',
    ]);
}

$password = getProvidedPassword();

if ($password === null || $password === '') {
    jsonResponse(401, [
        'ok' => false,
        'error' => 'Missing authentication password.',
    ]);
}

if (!isPasswordValid($password, $authPassword)) {
    jsonResponse(403, [
        'ok' => false,
        'error' => 'Invalid authentication password.',
    ]);
}

if (empty($_FILES)) {
    jsonResponse(400, [
        'ok' => false,
        'error' => 'No uploaded files found.',
    ]);
}

if (!is_dir($destinationDir) && !mkdir($destinationDir, 0775, true) && !is_dir($destinationDir)) {
    jsonResponse(500, [
        'ok' => false,
        'error' => 'Cannot create destination directory.',
    ]);
}

if (!is_writable($destinationDir)) {
    jsonResponse(500, [
        'ok' => false,
        'error' => 'Destination directory is not writable.',
    ]);
}

$uploadedFiles = normalizeUploadedFiles($_FILES);

if ($uploadedFiles === []) {
    jsonResponse(400, [
        'ok' => false,
        'error' => 'Uploaded payload is empty.',
    ]);
}

$saved = [];
$errors = [];

foreach ($uploadedFiles as $file) {
    $uploadError = (int) $file['error'];

    if ($uploadError !== UPLOAD_ERR_OK) {
        $errors[] = [
            'field' => $file['field'],
            'name' => $file['name'],
            'error' => uploadErrorMessage($uploadError),
        ];
        continue;
    }

    $temporaryPath = (string) $file['tmp_name'];

    if (!is_uploaded_file($temporaryPath)) {
        $errors[] = [
            'field' => $file['field'],
            'name' => $file['name'],
            'error' => 'Temporary uploaded file is not valid.',
        ];
        continue;
    }

    try {
        $targetPath = buildTargetPath($destinationDir, (string) $file['name']);
    } catch (Throwable $exception) {
        $errors[] = [
            'field' => $file['field'],
            'name' => $file['name'],
            'error' => 'Cannot generate a destination filename.',
        ];
        continue;
    }

    if (!move_uploaded_file($temporaryPath, $targetPath)) {
        $errors[] = [
            'field' => $file['field'],
            'name' => $file['name'],
            'error' => 'Unable to save the uploaded file.',
        ];
        continue;
    }

    $saved[] = [
        'field' => $file['field'],
        'original_name' => $file['name'],
        'saved_as' => basename($targetPath),
        'size' => (int) $file['size'],
    ];
}

$statusCode = $saved !== [] ? 200 : 400;

jsonResponse($statusCode, [
    'ok' => $errors === [],
    'saved_count' => count($saved),
    'error_count' => count($errors),
    'saved' => $saved,
    'errors' => $errors,
]);

function getProvidedPassword(): ?string
{
    $headerPassword = $_SERVER['HTTP_X_UPLOAD_PASSWORD'] ?? null;
    if (is_string($headerPassword) && $headerPassword !== '') {
        return trim($headerPassword);
    }

    $postPassword = $_POST['password'] ?? null;
    if (is_string($postPassword) && $postPassword !== '') {
        return trim($postPassword);
    }

    return null;
}

function isPasswordValid(string $password, string $expectedPassword): bool
{
    return $expectedPassword !== '' && hash_equals($expectedPassword, $password);
}

function normalizeUploadedFiles(array $files): array
{
    $normalized = [];

    foreach ($files as $field => $spec) {
        if (!isset($spec['name'], $spec['tmp_name'], $spec['error'], $spec['size'])) {
            continue;
        }

        if (!is_array($spec['name'])) {
            $normalized[] = [
                'field' => (string) $field,
                'name' => (string) $spec['name'],
                'tmp_name' => (string) $spec['tmp_name'],
                'error' => (int) $spec['error'],
                'size' => (int) $spec['size'],
            ];
            continue;
        }

        flattenUploadedField((string) $field, $spec, $normalized);
    }

    return $normalized;
}

function flattenUploadedField(string $field, array $spec, array &$normalized, array $path = []): void
{
    $current = getValueAtPath($spec['name'], $path);

    if (!is_array($current)) {
        $label = $field;
        if ($path !== []) {
            $label .= '[' . implode('][', array_map('strval', $path)) . ']';
        }

        $normalized[] = [
            'field' => $label,
            'name' => (string) getValueAtPath($spec['name'], $path),
            'tmp_name' => (string) getValueAtPath($spec['tmp_name'], $path),
            'error' => (int) getValueAtPath($spec['error'], $path),
            'size' => (int) getValueAtPath($spec['size'], $path),
        ];
        return;
    }

    foreach (array_keys($current) as $key) {
        $nextPath = $path;
        $nextPath[] = $key;
        flattenUploadedField($field, $spec, $normalized, $nextPath);
    }
}

function getValueAtPath($value, array $path)
{
    foreach ($path as $segment) {
        if (!is_array($value) || !array_key_exists($segment, $value)) {
            return null;
        }

        $value = $value[$segment];
    }

    return $value;
}

function buildTargetPath(string $destinationDir, string $originalName): string
{
    $safeName = sanitizeFilename($originalName);
    $pathInfo = pathinfo($safeName);
    $baseName = $pathInfo['filename'] ?? 'file';
    $extension = isset($pathInfo['extension']) && $pathInfo['extension'] !== ''
        ? '.' . $pathInfo['extension']
        : '';

    $targetPath = $destinationDir . DIRECTORY_SEPARATOR . $safeName;
    if (!file_exists($targetPath)) {
        return $targetPath;
    }

    do {
        $suffix = date('Ymd_His') . '_' . bin2hex(random_bytes(4));
        $targetPath = $destinationDir . DIRECTORY_SEPARATOR . $baseName . '_' . $suffix . $extension;
    } while (file_exists($targetPath));

    return $targetPath;
}

function sanitizeFilename(string $originalName): string
{
    $baseName = basename(str_replace('\\', '/', $originalName));
    $safeName = preg_replace('/[^A-Za-z0-9._-]+/', '_', $baseName);
    $safeName = $safeName !== null ? trim($safeName, '._-') : '';

    if ($safeName === '') {
        return 'file_' . date('Ymd_His');
    }

    return substr($safeName, 0, 180);
}

function uploadErrorMessage(int $errorCode): string
{
    switch ($errorCode) {
        case UPLOAD_ERR_INI_SIZE:
        case UPLOAD_ERR_FORM_SIZE:
            return 'Uploaded file is too large.';
        case UPLOAD_ERR_PARTIAL:
            return 'Uploaded file was only partially received.';
        case UPLOAD_ERR_NO_FILE:
            return 'No file was sent for this field.';
        case UPLOAD_ERR_NO_TMP_DIR:
            return 'Temporary upload directory is missing.';
        case UPLOAD_ERR_CANT_WRITE:
            return 'Server cannot write the uploaded file.';
        case UPLOAD_ERR_EXTENSION:
            return 'A PHP extension blocked the upload.';
        default:
            return 'Unknown upload error.';
    }
}

function jsonResponse(int $statusCode, array $payload): void
{
    http_response_code($statusCode);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}
