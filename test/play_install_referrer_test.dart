import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:play_install_referrer/play_install_referrer.dart';

void main() {
  const MethodChannel channel = MethodChannel(
    'com.chunkytofustudios.play_install_referrer',
  );

  final tester = TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    tester.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      MethodCall methodCall,
    ) async {
      return {
        'installReferrer': 'abc',
        'referrerClickTimestampSeconds': 1,
        'installBeginTimestampSeconds': 2,
        'referrerClickTimestampServerSeconds': 3,
        'installBeginTimestampServerSeconds': 4,
        'installVersion': null,
        'googlePlayInstantParam': true,
      };
    });
  });

  tearDown(() {
    tester.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('getInstallReferrer', () async {
    expect((await PlayInstallReferrer.installReferrer).installReferrer, 'abc');
    expect(
      (await PlayInstallReferrer.installReferrer).referrerClickTimestampSeconds,
      1,
    );
    expect(
      (await PlayInstallReferrer.installReferrer).installBeginTimestampSeconds,
      2,
    );
    expect(
      (await PlayInstallReferrer.installReferrer)
          .referrerClickTimestampServerSeconds,
      3,
    );
    expect(
      (await PlayInstallReferrer.installReferrer)
          .installBeginTimestampServerSeconds,
      4,
    );
    expect((await PlayInstallReferrer.installReferrer).installVersion, null);
    expect(
      (await PlayInstallReferrer.installReferrer).googlePlayInstantParam,
      true,
    );
  });
}
