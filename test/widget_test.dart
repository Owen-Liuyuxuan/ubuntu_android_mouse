import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ubuntu_remote/main.dart';

void main() {
  testWidgets('Disconnected remote disables controls and explains pairing', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('dev.owen.remote/hid'),
          (call) async => call.method == 'state'
              ? {'status': 'Ready', 'connected': false, 'devices': []}
              : null,
        );
    await tester.pumpWidget(const RemoteApp());
    await tester.pump();
    expect(find.text('Pair new host'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, 'Left'))
          .onPressed,
      isNull,
    );
    await tester.tap(find.text('Presenter'));
    await tester.pumpAndSettle();
    expect(find.text('Hold to aim'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets(
    'Connected controls send click, typed text, and slide navigation',
    (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.75;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('dev.owen.remote/hid'),
            (call) async {
              if (call.method == 'state')
                return {
                  'status': 'Connected',
                  'connected': true,
                  'devices': [],
                };
              calls.add(call);
              return null;
            },
          );
      await tester.pumpWidget(const RemoteApp());
      await tester.pump();
      await tester.tap(find.text('Left'));
      await tester.pump();
      expect(
        calls
            .where((c) => c.method == 'mouse')
            .map((c) => c.arguments['buttons']),
        [1, 0],
      );
      await tester.tap(find.text('Keyboard'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'hello');
      await tester.tap(find.text('Send text'));
      await tester.pump();
      expect(calls.lastWhere((c) => c.method == 'text').arguments, {
        'text': 'hello',
      });
      await tester.tap(find.text('Presenter'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Next'));
      await tester.tap(find.text('Next'));
      await tester.pump();
      expect(calls.lastWhere((c) => c.method == 'key').arguments, {
        'usage': 78,
        'modifier': 0,
      });
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
